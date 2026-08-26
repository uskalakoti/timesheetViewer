package com.timesheet.validator.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import com.timesheet.validator.domain.CellData;
import com.timesheet.validator.domain.SheetMeta;
import com.timesheet.validator.domain.UploadSession;
import com.timesheet.validator.dto.MergedRegionDto;
import com.timesheet.validator.dto.ResourceImportDto;
import com.timesheet.validator.dto.SowImportDto;
import com.timesheet.validator.repository.CellDataRepository;
import com.timesheet.validator.repository.SheetMetaRepository;
import com.timesheet.validator.repository.UploadSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.util.CellRangeAddress;

import static org.apache.poi.util.HexDump.toHex;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExcelParserService {

    /** Display format shown in the viewer grid: "01-Mar-26 (Sun)" */
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);

    /** ISO format stored in RAW_VALUE so ValidationService can always parse it */
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Excel date serial range used to detect date values unconditionally.
     *
     * Why we cannot rely on DateUtil.isCellDateFormatted() alone:
     *   - The Excel file may have date cells formatted as "General" or a
     *     numeric format string that POI does not classify as a date pattern.
     *   - In those cases isCellDateFormatted() returns false even though the
     *     numeric value is a date serial, so DataFormatter outputs "46082.0".
     *
     * Why we cannot rely on DataFormatter alone:
     *   - DataFormatter output depends on the cell's format string, which varies
     *     by Excel locale and user customisation: "3/1/26", "01-Mar-26",
     *     "46082.0" are all possible for the same underlying date value.
     *
     * Solution — dual-gate detection:
     *   A cell is treated as a date if EITHER condition is true:
     *     (a) DateUtil.isCellDateFormatted(cell) == true   (POI date format)
     *     (b) isDateSerial(numericValue) == true           (value in range)
     *
     * The range 35000–60000 covers 1995-08-09 to 2064-03-22.
     * This safely excludes typical non-date numbers in timesheets:
     *   hours (0.5–8), rates (100–500), PO values (millions) are all outside.
     */
    private static final double DATE_SERIAL_MIN = 35_000;   // 1995-08-09
    private static final double DATE_SERIAL_MAX = 60_000;   // 2064-03-22

    private final UploadSessionRepository sessionRepo;
    private final SheetMetaRepository     sheetMetaRepo;
    private final CellDataRepository      cellDataRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public String parse(MultipartFile file, List<String> selectedRules) throws Exception {
        return parse(file.getOriginalFilename(), file.getInputStream(), selectedRules, null);
    }

    /**
     * Parses workbook content from any source (used by the CR 5.3 transformation
     * engine to feed transformed in-memory workbooks through the same pipeline).
     *
     * @param uploadProject project/template selected at upload time (CR 5.1); may be null for legacy callers
     */
    @Transactional
    public String parse(String fileName, InputStream inputStream, List<String> selectedRules,
                        com.timesheet.validator.domain.UploadProject uploadProject) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        log.info("[Parser] Starting parse for file={} session={}", fileName, sessionId);

        try (InputStream is = inputStream;
             Workbook wb = new XSSFWorkbook(is)) {

            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            evaluator.setIgnoreMissingWorkbooks(true);

            int sheetCount = wb.getNumberOfSheets();
            List<SheetMeta> metas = new ArrayList<>();
            List<CellData>  cells = new ArrayList<>();

            for (int si = 0; si < sheetCount; si++) {
                Sheet  sheet     = wb.getSheetAt(si);
                String sheetName = sheet.getSheetName();

                int maxRow = sheet.getLastRowNum();
                int maxCol = 0;
                for (Row row : sheet) {
                    if (row != null) maxCol = Math.max(maxCol, row.getLastCellNum());
                }

                metas.add(SheetMeta.builder()
                        .sessionId(sessionId).sheetName(sheetName)
                        .sheetIndex(si).rowCount(maxRow + 1).colCount(maxCol)
                        .build());





                for (Row row : sheet) {
                    if (row == null) continue;
                    boolean isFirstRow = (row.getRowNum() == sheet.getFirstRowNum());

                    for (int ci = 0; ci < maxCol; ci++) {
                        Cell cell = row.getCell(ci, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);

                        String formula = null;
                        String display;
                        String raw;
                        String type = cell.getCellType().name();

                        // Resolve FORMULA cells to their underlying type first
                        CellType effectiveType = cell.getCellType();
                        double   numericValue  = 0;

                        if (effectiveType == CellType.FORMULA) {
                            formula = "=" + cell.getCellFormula();
                            type    = "FORMULA";
                            try {
                                CellValue cv = evaluator.evaluate(cell);
                                effectiveType = (cv != null) ? cv.getCellType() : CellType.BLANK;
                                numericValue  = (effectiveType == CellType.NUMERIC)
                                                ? cv.getNumberValue() : 0;
                            } catch (Exception e) {
                                log.warn("[Parser] Formula eval failed row={} col={}: {}",
                                        row.getRowNum(), ci, e.getMessage());
                                display = cell.getCellFormula();
                                raw     = display;
                                cells.add(buildCell(sessionId, sheetName, row.getRowNum(),
                                        ci, cell, display, raw, formula, type, isFirstRow));
                                continue;
                            }
                        } else if (effectiveType == CellType.NUMERIC) {
                            numericValue = cell.getNumericCellValue();
                        }

                        // ── DATE DETECTION (dual-gate) ────────────────────────────
                        // Gate A: POI says it's a date-formatted cell
                        // Gate B: numeric value falls in the plausible date serial range
                        // Either gate is sufficient to treat the cell as a date.
                        if (effectiveType == CellType.NUMERIC
                                && (DateUtil.isCellDateFormatted(cell)
                                    || isDateSerial(numericValue))) {
                            try {
                                LocalDate d = DateUtil
                                        .getLocalDateTime(numericValue, false)
                                        .toLocalDate();
                                display = formatDateDisplay(d);
                                raw     = d.format(ISO_FMT);
                                log.debug("[Parser] Date cell row={} col={} serial={} → {}",
                                        row.getRowNum(), ci, numericValue, raw);
                            } catch (Exception e) {
                                log.warn("[Parser] Date conversion failed row={} col={} val={}: {}",
                                        row.getRowNum(), ci, numericValue, e.getMessage());
                                display = new DataFormatter().formatCellValue(cell, evaluator);
                                raw     = display;
                            }

                        } else if (effectiveType == CellType.NUMERIC) {
                            // Plain numeric — format without date treatment
                            double d = numericValue;
                            display = (d == Math.floor(d) && !Double.isInfinite(d))
                                    ? String.valueOf((long) d)
                                    : String.valueOf(d);
                            raw = display;

                        } else {
                            // String, Boolean, Blank, Error — use DataFormatter
                            display = new DataFormatter().formatCellValue(cell, evaluator);
                            raw     = display;
                        }

                        cells.add(buildCell(sessionId, sheetName, row.getRowNum(),
                                ci, cell, display, raw, formula, type, isFirstRow));
                    }
                }
                log.info("[Parser] Sheet='{}' rows={} cols={}", sheetName, maxRow + 1, maxCol);
            }

            sheetMetaRepo.saveAll(metas);
            for (int i = 0; i < cells.size(); i += 500) {
                cellDataRepo.saveAll(cells.subList(i, Math.min(i + 500, cells.size())));
            }

            String enabledRules = selectedRules == null
                    ? ""
                    : String.join(",", selectedRules);

            sessionRepo.save(
                    UploadSession.builder()
                            .sessionId(sessionId)
                            .fileName(fileName)
                            .sheetCount(sheetCount)
                            .status("PARSED")
                            .enabledRules(enabledRules)
                            .uploadProject(uploadProject != null ? uploadProject.name() : null)
                            .build()
            );
        }

        log.info("[Parser] Complete session={}", sessionId);
        return sessionId;
    }


    //add leaverPlanner parser
    @Transactional
    public String parseLeavePlanner(MultipartFile file) throws Exception {

        String sessionId = UUID.randomUUID().toString();

        log.info(
                "[LeavePlanner Parser] Starting parse for file={} session={}",
                file.getOriginalFilename(),
                sessionId
        );

        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            FormulaEvaluator evaluator =
                    wb.getCreationHelper().createFormulaEvaluator();

            evaluator.setIgnoreMissingWorkbooks(true);

            int sheetCount = wb.getNumberOfSheets();

            List<SheetMeta> metas = new ArrayList<>();

            List<CellData> cells = new ArrayList<>();

            for (int si = 0; si < sheetCount; si++) {

                Sheet sheet = wb.getSheetAt(si);

                String sheetName = sheet.getSheetName();

                int maxRow = sheet.getLastRowNum();

                int maxCol = 0;

                for (Row row : sheet) {

                    if (row != null) {

                        maxCol =
                                Math.max(
                                        maxCol,
                                        row.getLastCellNum()
                                );
                    }
                }

                List<Integer> columnWidths = new ArrayList<>();

                for (int c = 0; c < maxCol; c++) {

                    columnWidths.add(sheet.getColumnWidth(c));

                }

                List<Short> rowHeights = new ArrayList<>();

                for (int r = 0; r <= maxRow; r++) {

                    Row row = sheet.getRow(r);

                    rowHeights.add(

                            row != null

                                    ? row.getHeight()

                                    : sheet.getDefaultRowHeight()

                    );
                }

                List<MergedRegionDto> mergedRegions = new ArrayList<>();

                for (CellRangeAddress region : sheet.getMergedRegions()) {

                    mergedRegions.add(

                            MergedRegionDto.builder()

                                    .firstRow(region.getFirstRow())

                                    .lastRow(region.getLastRow())

                                    .firstColumn(region.getFirstColumn())

                                    .lastColumn(region.getLastColumn())

                                    .build()

                    );
                }

//                metas.add(
//                        SheetMeta.builder()
//                                .sessionId(sessionId)
//                                .sheetName(sheetName)
//                                .sheetIndex(si)
//                                .rowCount(maxRow + 1)
//                                .colCount(maxCol)
//                                .build()
//                );

                metas.add(
                        SheetMeta.builder()
                                .sessionId(sessionId)
                                .sheetName(sheetName)
                                .sheetIndex(si)
                                .rowCount(maxRow + 1)
                                .colCount(maxCol)
                                .columnWidthsJson(
                                        objectMapper.writeValueAsString(columnWidths)
                                )
                                .rowHeightsJson(
                                        objectMapper.writeValueAsString(rowHeights)
                                )
                                .mergedRegionsJson(
                                        objectMapper.writeValueAsString(mergedRegions)
                                )
                                .build()
                );

                for (Row row : sheet) {

                    if (row == null) {
                        continue;
                    }

                    boolean isFirstRow =
                            row.getRowNum() == sheet.getFirstRowNum();

                    for (int ci = 0; ci < maxCol; ci++) {

                        Cell cell =
                                row.getCell(
                                        ci,
                                        Row.MissingCellPolicy.CREATE_NULL_AS_BLANK
                                );

                        String formula = null;
                        String display;
                        String raw;
                        String type = cell.getCellType().name();

                        CellType effectiveType = cell.getCellType();

                        double numericValue = 0;

                        if (effectiveType == CellType.FORMULA) {

                            formula = "=" + cell.getCellFormula();

                            CellValue cv =
                                    evaluator.evaluate(cell);

                            effectiveType =
                                    cv != null
                                            ? cv.getCellType()
                                            : CellType.BLANK;

                            if (effectiveType == CellType.NUMERIC) {
                                numericValue = cv.getNumberValue();
                            }

                        } else if (effectiveType == CellType.NUMERIC) {

                            numericValue =
                                    cell.getNumericCellValue();
                        }

                        if (effectiveType == CellType.NUMERIC &&
                                (DateUtil.isCellDateFormatted(cell)
                                        || isDateSerial(numericValue))) {

                            LocalDate d =
                                    DateUtil.getLocalDateTime(
                                                    numericValue,
                                                    false
                                            )
                                            .toLocalDate();

                            display =
                                    formatDateDisplay(d);

                            raw =
                                    d.format(ISO_FMT);

                        } else if (effectiveType == CellType.NUMERIC) {

                            display =
                                    new DataFormatter()
                                            .formatCellValue(
                                                    cell,
                                                    evaluator
                                            );

                            raw = display;

                        } else {

                            display =
                                    new DataFormatter()
                                            .formatCellValue(
                                                    cell,
                                                    evaluator
                                            );

                            raw = display;
                        }

                        cells.add(
                                buildCell(
                                        sessionId,
                                        sheetName,
                                        row.getRowNum(),
                                        ci,
                                        cell,
                                        display,
                                        raw,
                                        formula,
                                        type,
                                        isFirstRow
                                )
                        );
                    }
                }
            }

            sheetMetaRepo.saveAll(metas);

            log.info("Saved {} SheetMeta records", metas.size());

            for (SheetMeta meta : metas) {
                log.info("Sheet={}, Index={}, Session={}",
                        meta.getSheetName(),
                        meta.getSheetIndex(),
                        meta.getSessionId());
            }

            for (int i = 0; i < cells.size(); i += 500) {

                cellDataRepo.saveAll(
                        cells.subList(
                                i,
                                Math.min(i + 500, cells.size())
                        )
                );
            }

            sessionRepo.save(
                    UploadSession.builder()
                            .sessionId(sessionId)
                            .fileName(file.getOriginalFilename())
                            .sheetCount(sheetCount)
                            .status("LEAVE_PLANNER")
                            .enabledRules("")
                            .build()
            );
        }

        return sessionId;
    }


    /**
     * Parses the Resource Master workbook.
     */
    /**
     * Parses the Resource Master workbook and returns the extracted
     * employee data as ResourceImportDto objects.
     */
    public List<ResourceImportDto> parseResourceWorkbook(
            MultipartFile file) throws Exception {

        log.info("[Master Import] Parsing Resource workbook: {}",
                file.getOriginalFilename());

        List<ResourceImportDto> resources = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            // Locate the required sheet
            Sheet sheet = findResourceSheet(workbook);

            // Build header map
            Map<String, Integer> headerMap = buildHeaderMap(sheet);

            // Iterate over all data rows (skip header)
            for (int rowIndex = sheet.getFirstRowNum() + 1;
                 rowIndex <= sheet.getLastRowNum();
                 rowIndex++) {

                Row row = sheet.getRow(rowIndex);

                if (row == null) {
                    continue;
                }

                // Employee ID is our business key
                String employeeId = getCellValue(row, headerMap, "Employee Id", "Employee ID", "Emp ID");

                // Skip blank / summary rows
                if (employeeId == null || employeeId.trim().isEmpty()) {
                    continue;
                }

                ResourceImportDto dto = ResourceImportDto.builder()

                        .resourceId(employeeId)

                        .employeeName(
                                getCellValue(row, headerMap, "Employee Name", "Employee", "Resource Name")
                        )

                        .location(
                                getCellValue(row, headerMap, "Location", "Employee Location")
                        )

                        .assignedTeam(null)

//                        .project(
//                                getCellValue(row, headerMap, "SoW Name", "Project")
//                        )

//                        .project(null)

                        .project(
                                getCellValue(row, headerMap,
                                        "Sow Name",
                                        "SOW Name"
                                )
                        )

                        .sowDescription(
                                getCellValue(
                                        row,
                                        headerMap,
                                        "SoW Name"
                                )
                        )

                        .sowNumber(
                                getCellValue(row, headerMap, "SoW #", "SOW",
                                        "SOW No",
                                        "SOW Number")
                        )

                        .roleInSow(
                                getCellValue(
                                        row,
                                        headerMap,
                                        "Designation"
                                )
                        )

                        .build();

                resources.add(dto);
            }
        }

        log.info("[Master Import] Parsed {} resource records.",
                resources.size());

        return resources;
    }

    /**
     * Parses the SOW Master workbook.
     */
    /**
     * Parses the SOW Master workbook and returns the extracted
     * SOW data as SowImportDto objects.
     */
    public List<SowImportDto> parseSowWorkbook(
            MultipartFile file) throws Exception {

        log.info("[Master Import] Parsing SOW workbook: {}",
                file.getOriginalFilename());

        List<SowImportDto> sowList = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            // Find FY-* sheet
            Sheet sheet = findSowSheet(workbook);

            // Build dynamic header map
            Map<String, Integer> headerMap = buildHeaderMap(sheet);

            // Iterate over all data rows (skip header)
            for (int rowIndex = sheet.getFirstRowNum() + 1;
                 rowIndex <= sheet.getLastRowNum();
                 rowIndex++) {

                Row row = sheet.getRow(rowIndex);

                if (row == null) {
                    continue;
                }

                // Business Key
                String sowNumber = getCellValue(
                        row,
                        headerMap,
                        "SOW Number",
                        "SoW #",
                        "SoW Number",
                        "SOW"
                );

                // Skip blank rows
                if (sowNumber == null || sowNumber.trim().isEmpty()) {
                    continue;
                }

                SowImportDto dto = SowImportDto.builder()

//                        .project(
//                                getCellValue(
//                                        row,
//                                        headerMap,
//                                        "Project",
//                                        "Project Name"
//                                )
//                        )

                        .project(
                                getCellValue(row, headerMap,
                                        "Sow Name",
                                        "SOW Name"
                                )
                        )

                        .projectLocation(
                                getCellValue(
                                        row,
                                        headerMap,
                                        "Project Location",
                                        "Location"
                                )
                        )

                        .sowNumber(sowNumber)

                        .sowDescription(
                                getCellValue(
                                        row,
                                        headerMap,
                                        "SOW Description",
                                        "SoW Name",
                                        "Description"
                                )
                        )

                        .poNumber(
                                getCellValue(
                                        row,
                                        headerMap,
                                        "PO Number",
                                        "PO No"
                                )
                        )

                        .updatedPoNumber(
                                getCellValue(
                                        row,
                                        headerMap,
                                        "Updated PO Number",
                                        "Updated PO"
                                )
                        )

                        .poValue(
                                parseBigDecimal(
                                        getCellValue(
                                                row,
                                                headerMap,
                                                "PO Value"
                                        )
                                )
                        )

                        .sowStartDate(
                                getDateValue(
                                        row,
                                        headerMap,
                                        "SOW Start Date"
                                )
                        )

                        .sowEndDate(
                                getDateValue(
                                        row,
                                        headerMap,
                                        "SOW End Date"
                                )
                        )

//                        .poStartDate(
//                                parseLocalDate(
//                                        getCellValue(
//                                                row,
//                                                headerMap,
//                                                "PO Start Date"
//                                        )
//                                )
//                        )
//
//                        .poEndDate(
//                                parseLocalDate(
//                                        getCellValue(
//                                                row,
//                                                headerMap,
//                                                "PO End Date"
//                                        )
//                                )
//                        )

                        .poStartDate(
                                getDateValue(
                                        row,
                                        headerMap,
                                        "PO Start Date"
                                )
                        )

                        .poEndDate(
                                getDateValue(
                                        row,
                                        headerMap,
                                        "PO End Date"
                                )
                        )

                        .build();

                sowList.add(dto);
            }
        }

        log.info("[Master Import] Parsed {} SOW records.",
                sowList.size());

        return sowList;
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

//    private CellData buildCell(String sessionId, String sheetName, int row, int col, Cell cell,
//                               String display, String raw, String formula,
//                               String type, boolean isHeader) {
//        return CellData.builder()
//                .sessionId(sessionId).sheetName(sheetName)
//                .rowIdx(row).colIdx(col)
//                .displayValue(truncate(display, 2000))
//                .rawValue(truncate(raw, 2000))
//                .formula(truncate(formula, 2000))
//                .cellType(type)
//                .isHeader(isHeader)
//                .build();
//    }

    private CellData buildCell(String sessionId,
                               String sheetName,
                               int row,
                               int col,
                               Cell cell,
                               String display,
                               String raw,
                               String formula,
                               String type,
                               boolean isHeader) {

        CellStyle style = cell != null ? cell.getCellStyle() : null;

        Font font = null;
        if (style != null) {
            font = cell.getSheet()
                    .getWorkbook()
                    .getFontAt(style.getFontIndex());
        }

        CellData.CellDataBuilder builder = CellData.builder()
                .sessionId(sessionId)
                .sheetName(sheetName)
                .rowIdx(row)
                .colIdx(col)
                .displayValue(truncate(display, 2000))
                .rawValue(truncate(raw, 2000))
                .formula(truncate(formula, 2000))
                .cellType(type)
                .isHeader(isHeader);

        if (style != null) {
            builder
                    .horizontalAlignment(style.getAlignment().name())
                    .verticalAlignment(style.getVerticalAlignment().name())
                    .borderTop(style.getBorderTop().name())
                    .borderBottom(style.getBorderBottom().name())
                    .borderLeft(style.getBorderLeft().name())
                    .borderRight(style.getBorderRight().name());
        }

        if (font != null) {
            builder
                    .fontSize(font.getFontHeightInPoints())
                    .bold(font.getBold())
                    .italic(font.getItalic())
                    .backgroundColor(getBackgroundColor(style))
                    .fontColor(getFontColor(font));
        }

        return builder.build();
    }

    /**
     * Returns true if value is a plausible Excel date serial.
     * 35000 = 1995-08-09, 60000 = 2064-03-22.
     * Excludes all typical non-date values in timesheets (hours, rates, counts).
     */
    private boolean isDateSerial(double value) {
        return value >= DATE_SERIAL_MIN && value <= DATE_SERIAL_MAX;
    }

    /** "01-Mar-26 (Sun)" — shown in the viewer and used as hover tooltip */
    private String formatDateDisplay(LocalDate d) {
        String day = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return d.format(DISPLAY_FMT) + " (" + day + ")";
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }


    private String getBackgroundColor(CellStyle style) {

        if (!(style instanceof XSSFCellStyle)) {
            return null;
        }

        XSSFColor color =
                ((XSSFCellStyle) style).getFillForegroundColorColor();

        return toHex(color);
    }

    private String getFontColor(Font font) {

        if (!(font instanceof XSSFFont)) {
            return null;
        }

        XSSFColor color =
                ((XSSFFont) font).getXSSFColor();

        return toHex(color);
    }

    private String toHex(XSSFColor color) {

        if (color == null) {
            return null;
        }

        byte[] rgb = color.getRGB();

        if (rgb == null) {
            return null;
        }

        return String.format(
                "#%02X%02X%02X",
                rgb[0] & 0xFF,
                rgb[1] & 0xFF,
                rgb[2] & 0xFF
        );
    }


    //helper methods for master data

    /**
     * Finds the Resource Master sheet from the uploaded Provisional Billing workbook.
     *
     * Expected sheet examples:
     *   - Jul - Provisional
     *   - Aug - Provisional
     *   - Sep - Provisional
     *
     * Ignores:
     *   - Forecast sheets
     *   - Pivot sheets
     */
    private Sheet findResourceSheet(Workbook workbook) {

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {

            Sheet sheet = workbook.getSheetAt(i);

            String sheetName = sheet.getSheetName().toLowerCase(Locale.ENGLISH);

            if (sheetName.contains("provisional")
                    && !sheetName.contains("forecast")
                    && !sheetName.contains("pivot")) {

                log.info("[Master Import] Resource sheet found: {}", sheet.getSheetName());

                return sheet;
            }
        }

        throw new IllegalArgumentException(
                "Resource sheet not found in workbook."
        );
    }

    /**
     * Finds the SOW Master sheet from the uploaded PO Balance workbook.
     *
     * Expected sheet examples:
     *   - FY-26
     *   - FY-27
     *   - FY-28
     */
    private Sheet findSowSheet(Workbook workbook) {

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {

            Sheet sheet = workbook.getSheetAt(i);

            String sheetName = sheet.getSheetName().trim().toUpperCase(Locale.ENGLISH);

            if (sheetName.startsWith("FY")) {

                log.info("[Master Import] SOW sheet found: {}", sheet.getSheetName());

                return sheet;
            }
        }

        throw new IllegalArgumentException(
                "SOW sheet not found in workbook."
        );
    }

    /**
     * Builds a map of Excel header names to their column indexes.
     *
     * Example:
     * "Employee Name" -> 3
     * "Employee Id"   -> 2
     * "Location"      -> 5
     */
    private Map<String, Integer> buildHeaderMap(Sheet sheet) {

        Map<String, Integer> headerMap = new LinkedHashMap<>();

        Row headerRow = sheet.getRow(sheet.getFirstRowNum());

        if (headerRow == null) {
            return headerMap;
        }

        DataFormatter formatter = new DataFormatter();

        for (Cell cell : headerRow) {

            String header = formatter.formatCellValue(cell).trim();

            if (!header.isEmpty()) {
//                headerMap.put(header, cell.getColumnIndex());
//                headerMap.put(
//                        header.trim().toLowerCase(Locale.ENGLISH),
//                        cell.getColumnIndex()
//                );

                headerMap.put(
                        normalizeHeader(header),
                        cell.getColumnIndex()
                );
            }
        }

        return headerMap;
    }

    /**
     * Returns the display value of a cell using the header name.
     *
     * If the header is not found, an empty string is returned.
     */
//    private String getCellValue(Row row,
//                                Map<String, Integer> headerMap,
//                                String headerName) {
//
//        Integer columnIndex = headerMap.get(headerName);
//
//        if (columnIndex == null) {
//            return "";
//        }
//
//        Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
//
//        return new DataFormatter().formatCellValue(cell).trim();
//    }


    /**
     * Returns the cell value for the first matching header name.
     *
     * Supports multiple aliases for the same logical field.
     *
     * Example:
     * getCellValue(row, headerMap,
     *      "Employee Id",
     *      "Employee ID",
     *      "Emp ID");
     */
    private String getCellValue(Row row,
                                Map<String, Integer> headerMap,
                                String... headerNames) {

        DataFormatter formatter = new DataFormatter();

        for (String headerName : headerNames) {

//            Integer columnIndex = headerMap.get(headerName);

//            Integer columnIndex =
//                    headerMap.get(
//                            headerName.toLowerCase(Locale.ENGLISH)
//                    );

            Integer columnIndex =
                    headerMap.get(
                            normalizeHeader(headerName)
                    );

            if (columnIndex != null) {

                Cell cell = row.getCell(
                        columnIndex,
                        Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);

                return formatter.formatCellValue(cell).trim();
            }
        }

        return "";
    }

    private BigDecimal parseBigDecimal(String value) {

        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        try {

            String cleaned = value
                    .replace(",", "")
                    .replace("$", "")
                    .trim();

            return new BigDecimal(cleaned);

        } catch (Exception ex) {

            log.warn("Unable to parse BigDecimal: {}", value);

            return null;
        }
    }

    private LocalDate parseLocalDate(String value) {

        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        List<DateTimeFormatter> formats = List.of(

                DateTimeFormatter.ofPattern("dd-MMM-yy"),

                DateTimeFormatter.ofPattern("dd-MMM-yyyy"),

                DateTimeFormatter.ofPattern("dd/MM/yyyy"),

                DateTimeFormatter.ISO_LOCAL_DATE
        );

        for (DateTimeFormatter formatter : formats) {

            try {

                return LocalDate.parse(value.trim(), formatter);

            } catch (Exception ignored) {
            }
        }

        log.warn("Unable to parse date: {}", value);

        return null;
    }

    private LocalDate getDateValue(
            Row row,
            Map<String, Integer> headerMap,
            String... headerNames) {

        Integer columnIndex = null;

        for (String headerName : headerNames) {
            columnIndex = headerMap.get(normalizeHeader(headerName));

            if (columnIndex != null) {
                break;
            }
        }

        if (columnIndex == null) {
            return null;
        }

        Cell cell = row.getCell(
                columnIndex,
                Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );

        if (cell == null) {
            return null;
        }

        try {

            /*
             * Excel stores dates as numeric serial values.
             */
            if (cell.getCellType() == CellType.NUMERIC) {

                if (DateUtil.isCellDateFormatted(cell)
                        || isDateSerial(cell.getNumericCellValue())) {

                    return DateUtil
                            .getLocalDateTime(
                                    cell.getNumericCellValue(),
                                    false
                            )
                            .toLocalDate();
                }
            }

            /*
             * Handle dates stored as text.
             */
            String value = new DataFormatter()
                    .formatCellValue(cell)
                    .trim();

            if (value.isBlank()) {
                return null;
            }

            return parseFlexibleDate(value);

        } catch (Exception ex) {

            log.warn(
                    "Unable to parse date for header(s) {}. Value={}",
                    String.join(", ", headerNames),
                    cell
            );

            return null;
        }
    }

    private LocalDate parseFlexibleDate(String value) {

        List<DateTimeFormatter> formatters = List.of(

                DateTimeFormatter.ISO_LOCAL_DATE,

                DateTimeFormatter.ofPattern(
                        "d-MMM-yy",
                        Locale.ENGLISH
                ),

                DateTimeFormatter.ofPattern(
                        "dd-MMM-yy",
                        Locale.ENGLISH
                ),

                DateTimeFormatter.ofPattern(
                        "d-MMM-yyyy",
                        Locale.ENGLISH
                ),

                DateTimeFormatter.ofPattern(
                        "dd-MMM-yyyy",
                        Locale.ENGLISH
                ),

                DateTimeFormatter.ofPattern(
                        "M/d/yy",
                        Locale.ENGLISH
                ),

                DateTimeFormatter.ofPattern(
                        "M/d/yyyy",
                        Locale.ENGLISH
                )
        );

        for (DateTimeFormatter formatter : formatters) {

            try {
                return LocalDate.parse(value, formatter);
            } catch (Exception ignored) {
            }
        }

        throw new IllegalArgumentException(
                "Unsupported date format: " + value
        );
    }

    private String normalizeHeader(String header) {

        if (header == null) {
            return "";
        }

        return header
                .trim()
                .toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]", "");
    }

}
