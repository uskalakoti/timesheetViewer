package com.timesheet.validator.service;

import com.timesheet.validator.domain.CellData;
import com.timesheet.validator.domain.SheetMeta;
import com.timesheet.validator.domain.UploadProject;
import com.timesheet.validator.domain.UploadSession;
import com.timesheet.validator.domain.ValidationIssue;
import com.timesheet.validator.dto.CellDto;
import com.timesheet.validator.dto.MergedRegionDto;
import com.timesheet.validator.dto.SheetDto;
import com.timesheet.validator.repository.CellDataRepository;
import com.timesheet.validator.repository.SheetMetaRepository;
import com.timesheet.validator.repository.UploadSessionRepository;
import com.timesheet.validator.repository.ValidationIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class SheetViewService {

    private final SheetMetaRepository sheetMetaRepo;
    private final CellDataRepository cellDataRepo;
    private final ValidationIssueRepository issueRepo;
    private final UploadSessionRepository uploadSessionRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns all sheets for the session, each with a 2D grid of CellDto.
     * Validation issues are overlaid on matching cells.
     */
    public List<SheetDto> getSheets(String sessionId) {
        List<SheetMeta> metas = sheetMetaRepo.findBySessionIdOrderBySheetIndex(sessionId);

        Set<Integer> naTimesheetColumns = naTimesheetColumnsFor(sessionId);

        // Build an issue lookup: sheetName → rowIdx → colIdx → issue
        Map<String, Map<Integer, Map<Integer, List<ValidationIssue>>>> issueMap = buildIssueMap(sessionId);

        List<SheetDto> result = new ArrayList<>();
        for (SheetMeta meta : metas) {
            result.add(buildSheet(sessionId, meta, issueMap, naTimesheetColumns));
        }
        return result;
    }

    /**
     * Builds a single sheet's grid on demand. Backs the lazy per-tab API so the
     * viewer no longer has to serialise every sheet into one giant page.
     */
    public SheetDto getSheet(String sessionId, int sheetIndex) {
        SheetMeta meta = sheetMetaRepo.findBySessionIdOrderBySheetIndex(sessionId).stream()
                .filter(m -> m.getSheetIndex() != null && m.getSheetIndex() == sheetIndex)
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Sheet index " + sheetIndex + " not found for session " + sessionId));
        Set<Integer> naTimesheetColumns = naTimesheetColumnsFor(sessionId);
        return buildSheet(sessionId, meta, buildIssueMap(sessionId), naTimesheetColumns);
    }

    /**
     * Columns of the normalized Timesheet grid that this session's upload
     * format does not carry (rendered as "Not Applicable", excluded from validation).
     */
    private Set<Integer> naTimesheetColumnsFor(String sessionId) {
        return uploadSessionRepo.findBySessionId(sessionId)
                .map(s -> UploadProject.naTimesheetColumnsOf(s.getUploadProject()))
                .orElse(Set.of());
    }

    private SheetDto buildSheet(String sessionId, SheetMeta meta,
                               Map<String, Map<Integer, Map<Integer, List<ValidationIssue>>>> issueMap,
                               Set<Integer> naTimesheetColumns) {
            List<CellData> cells = cellDataRepo
                    .findBySessionIdAndSheetNameOrderByRowIdxAscColIdxAsc(sessionId, meta.getSheetName());

            // Group by rowIdx
            Map<Integer, List<CellData>> byRow = cells.stream()
                    .collect(Collectors.groupingBy(CellData::getRowIdx, TreeMap::new, Collectors.toList()));

            // Find max col for consistent grid width
//            int maxCol = cells.stream().mapToInt(CellData::getColIdx).max().orElse(0) + 1;
                int maxCol = meta.getColCount();


//            List<List<CellDto>> rows = new ArrayList<>();
//            for (Map.Entry<Integer, List<CellData>> rowEntry : byRow.entrySet()) {
//                int ri = rowEntry.getKey();
//                Map<Integer, CellData> colMap = rowEntry.getValue().stream()
//                        .collect(Collectors.toMap(CellData::getColIdx, c -> c, (a, b) -> a));
//
//
//                CellData employeeCell = colMap.get(0);
//
//                if(employeeCell != null){
//                    log.info(
//                            "VIEWER ROW -> rowIdx={} employee={}",
//                            ri,
//                            employeeCell.getDisplayValue()
//                    );
//                }
//
//
//                List<CellDto> row = new ArrayList<>();
//                for (int ci = 0; ci < maxCol; ci++) {
//                    CellData c = colMap.get(ci);
//                    String display = c != null ? nvl(c.getDisplayValue()) : "";
//                    String formula = c != null ? c.getFormula() : null;
//                    String type    = c != null ? nvl(c.getCellType()) : "BLANK";
//                    boolean header = c != null && Boolean.TRUE.equals(c.getIsHeader());
//
//                    // Overlay validation issue if any
//                    List<ValidationIssue> issues =
//                            getIssues(issueMap,
//                                    meta.getSheetName(),
//                                    ri,
//                                    ci);
//
//                    List<String> validationMessages = issues.stream()
//                            .map(ValidationIssue::getMessage)
//                            .collect(Collectors.toList());
//
//                    List<String> severities = issues.stream()
//                            .map(ValidationIssue::getSeverity)
//                            .collect(Collectors.toList());
//
//                    String highestSeverity = null;
//
//                    if (severities.contains("CRITICAL")) {
//                        highestSeverity = "CRITICAL";
//                    }
//                    else if (severities.contains("WARNING")) {
//                        highestSeverity = "WARNING";
//                    }
//
//                    boolean employeeIssue =
//                            ci == 0 &&
//                                    issues != null &&
//                                    !issues.isEmpty();
//
//                    // Build formula tooltip
//                    String tooltip = buildTooltip(formula, display, type);
//
//                    row.add(CellDto.builder()
//                            .rowIdx(ri).colIdx(ci)
//                            .displayValue(display)
//                            .formula(tooltip)
//                            .cellType(type)
//                            .isHeader(header)
//                            .validationMessages(validationMessages)
//                            .severities(severities)
//                            .highestSeverity(highestSeverity)
//                            .employeeIssue(employeeIssue)
//                            .backgroundColor(c != null ? c.getBackgroundColor() : null)
//                            .fontColor(c != null ? c.getFontColor() : null)
//                            .fontSize(c != null ? c.getFontSize() : null)
//                            .bold(c != null && Boolean.TRUE.equals(c.getBold()))
//                            .italic(c != null && Boolean.TRUE.equals(c.getItalic()))
//                            .horizontalAlignment(c != null ? c.getHorizontalAlignment() : null)
//                            .verticalAlignment(c != null ? c.getVerticalAlignment() : null)
//                            .borderTop(c != null ? c.getBorderTop() : null)
//                            .borderBottom(c != null ? c.getBorderBottom() : null)
//                            .borderLeft(c != null ? c.getBorderLeft() : null)
//                            .borderRight(c != null ? c.getBorderRight() : null)
//                            .build());
//                }
//                rows.add(row);
//            }



        List<List<CellDto>> rows = new ArrayList<>();

        for (int ri = 0; ri < meta.getRowCount(); ri++) {

            List<CellData> currentRow =
                    byRow.getOrDefault(
                            ri,
                            Collections.emptyList()
                    );

            Map<Integer, CellData> colMap =
                    currentRow.stream()
                            .collect(Collectors.toMap(
                                    CellData::getColIdx,
                                    c -> c,
                                    (a,b)->a
                            ));

            List<CellDto> row = new ArrayList<>();

            for (int ci = 0; ci < maxCol; ci++) {

                CellData c = colMap.get(ci);

                // existing cell creation code stays exactly the same
                String display = c != null ? nvl(c.getDisplayValue()) : "";

                // Fields absent from the source format render as "Not Applicable"
                boolean notApplicableCell = false;
                if ("Timesheet".equalsIgnoreCase(meta.getSheetName())
                        && display.isBlank()
                        && naTimesheetColumns.contains(ci)) {
                    display = "Not Applicable";
                    notApplicableCell = true;
                }

                String formula = c != null ? c.getFormula() : null;
                String type    = c != null ? nvl(c.getCellType()) : "BLANK";
                boolean header = c != null && Boolean.TRUE.equals(c.getIsHeader());

                // Overlay validation issue if any
                List<ValidationIssue> issues =
                        getIssues(issueMap,
                                meta.getSheetName(),
                                ri,
                                ci);

                List<String> validationMessages = issues.stream()
                        .map(ValidationIssue::getMessage)
                        .collect(Collectors.toList());

                List<String> severities = issues.stream()
                        .map(ValidationIssue::getSeverity)
                        .collect(Collectors.toList());

                String highestSeverity = null;

                if (severities.contains("CRITICAL")) {
                    highestSeverity = "CRITICAL";
                }
                else if (severities.contains("WARNING")) {
                    highestSeverity = "WARNING";
                }

                boolean employeeIssue =
                        ci == 0 &&
                                issues != null &&
                                !issues.isEmpty();

                // Build formula tooltip
                String tooltip = buildTooltip(formula, display, type);

                row.add(CellDto.builder()
                        .rowIdx(ri).colIdx(ci)
                        .displayValue(display)
                        .formula(tooltip)
                        .cellType(type)
                        .isHeader(header)
                        .validationMessages(validationMessages)
                        .severities(severities)
                        .highestSeverity(highestSeverity)
                        .employeeIssue(employeeIssue)
                        .backgroundColor(c != null ? c.getBackgroundColor() : null)
                        .fontColor(c != null ? c.getFontColor() : null)
                        .fontSize(c != null ? c.getFontSize() : null)
                        .bold(c != null && Boolean.TRUE.equals(c.getBold()))
                        .notApplicable(notApplicableCell)
                        .italic(c != null && Boolean.TRUE.equals(c.getItalic()))
                        .horizontalAlignment(c != null ? c.getHorizontalAlignment() : null)
                        .verticalAlignment(c != null ? c.getVerticalAlignment() : null)
                        .borderTop(c != null ? c.getBorderTop() : null)
                        .borderBottom(c != null ? c.getBorderBottom() : null)
                        .borderLeft(c != null ? c.getBorderLeft() : null)
                        .borderRight(c != null ? c.getBorderRight() : null)
                        .build());

            }

            rows.add(row);

        }


//            return SheetDto.builder()
//                    .sheetName(meta.getSheetName())
//                    .sheetIndex(meta.getSheetIndex())
//                    .rowCount(meta.getRowCount())
//                    .colCount(maxCol)
//                    .rows(rows)
//                    .build();

        return SheetDto.builder()
                .sheetName(meta.getSheetName())
                .sheetIndex(meta.getSheetIndex())
                .rowCount(meta.getRowCount())
//                .colCount(meta.getColCount())
                .colCount(maxCol)
                .rows(rows)
                .columnWidths(parseColumnWidths(meta.getColumnWidthsJson()))
                .rowHeights(parseRowHeights(meta.getRowHeightsJson()))
                .mergedRegions(parseMergedRegions(meta.getMergedRegionsJson()))
                .build();
    }

    public List<SheetMeta> getSheetMetas(String sessionId) {
        return sheetMetaRepo.findBySessionIdOrderBySheetIndex(sessionId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildTooltip(String formula, String display, String type) {
        if (formula != null && !formula.isBlank()) {
            return formula; // e.g. "=SUM(B2:B10)"
        }
        // For key business fields, annotate with what the value means
        return null;
    }

    private Map<String, Map<Integer, Map<Integer, List<ValidationIssue>>>> buildIssueMap(String sessionId) {
        List<ValidationIssue> issues = issueRepo.findBySessionId(sessionId);
        Map<String, Map<Integer, Map<Integer, List<ValidationIssue>>>> map = new HashMap<>();
        for (ValidationIssue issue : issues) {
            if (issue.getRowIdx() == null || issue.getColIdx() == null
                    || issue.getRowIdx() < 0 || issue.getColIdx() < 0) continue;
            map.computeIfAbsent(issue.getSheetName(), k -> new HashMap<>())
               .computeIfAbsent(issue.getRowIdx(), k -> new HashMap<>())
                    .computeIfAbsent(issue.getColIdx(),
                            k -> new ArrayList<>())
                    .add(issue);
        }
        return map;
    }

//    private List<ValidationIssue> getIssues(
//            Map<String, Map<Integer, Map<Integer, List<ValidationIssue>>>> map,
//            String sheet,
//            int row,
//            int col) {
//
//        return Optional.ofNullable(map.get(sheet))
//                .map(r -> r.get(row))
//                .map(c -> c.get(col))
//                .orElse(Collections.emptyList());
//    }


    private List<ValidationIssue> getIssues(
            Map<String, Map<Integer, Map<Integer, List<ValidationIssue>>>> map,
            String sheet,
            int row,
            int col) {

        List<ValidationIssue> issues =
                Optional.ofNullable(map.get(sheet))
                        .map(r -> r.get(row))
                        .map(c -> c.get(col))
                        .orElse(Collections.emptyList());

//        if (!issues.isEmpty()) {
//
//            log.info(
//                    "CELL HIGHLIGHT -> sheet={} row={} col={} issues={}",
//                    sheet,
//                    row,
//                    col,
//                    issues.size()
//            );
//        }

        if (!issues.isEmpty()) {

            log.info(
                    "CELL HIGHLIGHT -> sheet={} row={} col={} rule={} msg={}",
                    sheet,
                    row,
                    col,
                    issues.get(0).getRuleId(),
                    issues.get(0).getMessage()
            );
        }

        return issues;
    }

    private String nvl(String s) { return s == null ? "" : s; }

    private List<Integer> parseColumnWidths(String json) {

        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {

            return objectMapper.readValue(
                    json,
                    new TypeReference<List<Integer>>() {}
            );

        } catch (Exception e) {

            log.warn("Unable to parse column widths", e);

            return Collections.emptyList();
        }
    }

    private List<Short> parseRowHeights(String json) {

        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {

            return objectMapper.readValue(
                    json,
                    new TypeReference<List<Short>>() {}
            );

        } catch (Exception e) {

            log.warn("Unable to parse row heights", e);

            return Collections.emptyList();
        }
    }

    private List<MergedRegionDto> parseMergedRegions(String json) {

        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {

            return objectMapper.readValue(
                    json,
                    new TypeReference<List<MergedRegionDto>>() {}
            );

        } catch (Exception e) {

            log.warn("Unable to parse merged regions", e);

            return Collections.emptyList();
        }
    }
}
