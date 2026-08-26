package com.timesheet.validator.service;

import com.timesheet.validator.domain.UploadProject;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * CR 5.3 — Generalized Timesheet Format Transformation Engine.
 *
 * <p>Transforms a "Generalized Timesheet" workbook (e.g. the IATA TIMATIC
 * layout: one sheet per person plus Summary/Commercial/Admin sheets) into
 * the standard Sydney SoftDev Timesheet structure, so that every existing
 * validation workflow (Timesheet / Pivot / Project-wise / Summary /
 * Commercial) can run unchanged and no additional rule sets are required.</p>
 *
 * <p>Mapping performed:</p>
 * <ul>
 *   <li>All per-person sheets are consolidated into one flat {@code Timesheet}
 *       sheet with the 11 standard columns (Date, Name, Assigned Team,
 *       Project, Sub Project, Project Code, Location : Country, Hours, Task,
 *       Company, SOW). Fields not present in the generalized layout
 *       (Assigned Team, Sub Project, Project Code, Country) are left blank.</li>
 *   <li>A {@code Pivot} sheet (name × date hours matrix with Grand Total and
 *       Days columns) is synthesized from the consolidated entries.</li>
 *   <li>A {@code Projectwise} sheet (project totals table + project hierarchy
 *       table) is synthesized from the consolidated entries.</li>
 *   <li>{@code Summary} is normalized to the Sydney SoftDev column layout by
 *       inserting an empty "Travel Expense" column so Total Amount lands in
 *       column K (index 10).</li>
 *   <li>{@code Commercial} is normalized by stripping preamble rows so that
 *       key/value pairs start at row 0 exactly like a native Sydney SoftDev
 *       file.</li>
 * </ul>
 *
 * <p>The returned workbook bytes are then parsed and validated exactly like a
 * natively uploaded Sydney SoftDev file.</p>
 */
@Service
@Slf4j
public class GeneralizedTimesheetTransformer {

    /** Header labels of the standard Sydney SoftDev Timesheet sheet. */
    private static final String[] TIMESHEET_HEADERS = {
            "Date (Mandatory)", "Name (Mandatory)", "Assigned Team (Mandatory)",
            "Project (Mandatory)", "Sub Project (if applicable)",
            "Project Code (Mandatory)", "Location : Country (Mandatory)",
            "Hours (Mandatory)", "Task (May be required)", "Company (Mandatory)",
            "SOW (Mandatory)"
    };

    /** Column indexes within the standard Timesheet sheet. */
    private static final int COL_DATE = 0, COL_NAME = 1, COL_TEAM = 2, COL_PROJECT = 3,
            COL_SUB_PROJECT = 4, COL_PROJECT_CODE = 5, COL_COUNTRY = 6, COL_HOURS = 7,
            COL_TASK = 8, COL_COMPANY = 9, COL_SOW = 10;

    /** Meta sheets of the generalized workbook that are not per-person data. */
    private static final List<String> META_SHEETS = List.of("Summary", "Commercial", "Admin");

    /** Column layout of generalized per-person sheets: Date|Name|Project|Hours|Description|Company|SOW. */
    private static final int P_COL_DATE = 0, P_COL_NAME = 1, P_COL_PROJECT = 2,
            P_COL_HOURS = 3, P_COL_TASK = 4, P_COL_COMPANY = 5, P_COL_SOW = 6;

    /**
     * Detects a generalized per-person sheet by its header signature:
     * Date | Name | Project | Hours | Description... | Company | SOW...
     */
    static boolean isPersonSheet(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) return false;
        String c0 = text(header.getCell(0));
        String c1 = text(header.getCell(1));
        String c3 = text(header.getCell(3));
        String c6 = text(header.getCell(6));
        return c0.startsWith("Date") && c1.startsWith("Name")
                && c3.startsWith("Hours") && c6.contains("SOW");
    }

    /** Transforms the given generalized workbook into Sydney SoftDev structure. */
    public byte[] transform(Workbook source, UploadProject uploadProject) throws Exception {
        log.info("[Transformer] Transforming {} workbook to Sydney SoftDev format", uploadProject);

        List<String[]> entries = new ArrayList<>(); // date ISO | name | project | hours | task | company | sow
        TreeSet<LocalDateTime> dates = new TreeSet<>();
        int consolidated = 0;

        org.apache.poi.ss.usermodel.FormulaEvaluator evaluator =
                source.getCreationHelper().createFormulaEvaluator();

        for (int si = 0; si < source.getNumberOfSheets(); si++) {
            Sheet sheet = source.getSheetAt(si);
            if (!META_SHEETS.contains(sheet.getSheetName()) && isPersonSheet(sheet)) {
                consolidated += collectEntries(sheet, sheet.getSheetName(), entries, dates, evaluator);
            }
        }
        log.info("[Transformer] Consolidated {} entries from person sheets", consolidated);
        if (consolidated == 0) {
            throw new IllegalStateException(
                    "No per-person timesheet sheets found. The uploaded file does not match "
                    + "the generalized timesheet layout.");
        }

        try (Workbook out = new XSSFWorkbook()) {
            buildTimesheet(out.createSheet("Timesheet"), entries);
            buildPivot(out.createSheet("Pivot"), entries, dates);
            buildProjectWise(out.createSheet("Projectwise"), entries);
            normalizeSummary(source, out);
            normalizeCommercial(source, out);
            copyAdminSheet(source, out);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.write(bos);
            return bos.toByteArray();
        } finally {
            source.close();
        }
    }

    // ── Person-sheet extraction ─────────────────────────────────────────────

    /** Collects data rows from one person sheet into flat standard entries. */
    private int collectEntries(Sheet sheet, String fallbackName,
                               List<String[]> entries, TreeSet<LocalDateTime> dates,
                               org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        int count = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            LocalDateTime date = parseDate(row.getCell(P_COL_DATE), evaluator);
            if (date == null) continue;                       // not a data row
            String name = firstNonBlank(text(row.getCell(P_COL_NAME), evaluator), fallbackName);
            String hoursText = text(row.getCell(P_COL_HOURS), evaluator);
            String task = text(row.getCell(P_COL_TASK), evaluator);
            if (isBlank(name) && isBlank(hoursText) && isBlank(task)) continue; // blank filler row

            String hours = numeric(row.getCell(P_COL_HOURS), evaluator);
            entries.add(new String[]{
                    date.toLocalDate().toString(),          // ISO raw value
                    name,
                    "",                                     // Assigned Team — not present in generalized layout
                    text(row.getCell(P_COL_PROJECT), evaluator),   // Project
                    "",                                     // Sub Project — not present
                    "",                                     // Project Code — not present
                    "",                                     // Location : Country — not present
                    hours,
                    task,
                    text(row.getCell(P_COL_COMPANY), evaluator),   // Company
                    text(row.getCell(P_COL_SOW), evaluator)        // SOW
            });
            dates.add(date.withNano(0));
            count++;
        }
        return count;
    }

    /** Parses a date cell: real/formula date cells or strings like "06/01/2026, Monday". */
    private LocalDateTime parseDate(org.apache.poi.ss.usermodel.Cell cell,
                                    org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell == null) return null;
        try {
            CellType effective = effectiveType(cell, evaluator);
            switch (effective) {
                case NUMERIC:
                    double v = numericValue(cell, evaluator);
                    return org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(v, false).withNano(0);
                case STRING:
                    return parseDateString(stringValue(cell, evaluator));
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateString(String value) {
        if (value == null) return null;
        String v = value.trim();
        // Formats: "06/01/2026, Monday" or "2026-06-01" or "01-Jun-26"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(v);
        if (m.find()) {
            return LocalDateTime.of(Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0, 0);
        }
        try {
            return LocalDateTime.parse(v.substring(0, Math.min(10, v.length())));
        } catch (Exception ignored) { }
        try {
            return java.time.LocalDate.parse(v.substring(0, Math.min(9, v.length())),
                    java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy",
                            java.util.Locale.ENGLISH)).atStartOfDay();
        } catch (Exception ignored) { }
        return null;
    }

    // ── Sheet builders ───────────────────────────────────────────────────────

    /** Flat Timesheet sheet with the 11 standard columns. */
    private void buildTimesheet(Sheet sheet, List<String[]> entries) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < TIMESHEET_HEADERS.length; i++) {
            header.createCell(i).setCellValue(TIMESHEET_HEADERS[i]);
        }
        int r = 1;
        for (String[] e : entries) {
            Row row = sheet.createRow(r++);
            row.createCell(COL_DATE).setCellValue(java.time.LocalDate.parse(e[COL_DATE]).atStartOfDay());
            row.createCell(COL_NAME).setCellValue(nullSafe(e[COL_NAME]));
            row.createCell(COL_TEAM).setCellValue(nullSafe(e[COL_TEAM]));
            row.createCell(COL_PROJECT).setCellValue(nullSafe(e[COL_PROJECT]));
            row.createCell(COL_SUB_PROJECT).setCellValue(nullSafe(e[COL_SUB_PROJECT]));
            row.createCell(COL_PROJECT_CODE).setCellValue(nullSafe(e[COL_PROJECT_CODE]));
            row.createCell(COL_COUNTRY).setCellValue(nullSafe(e[COL_COUNTRY]));
            if (!e[COL_HOURS].isBlank()) {
                try { row.createCell(COL_HOURS).setCellValue(Double.parseDouble(e[COL_HOURS])); }
                catch (NumberFormatException ex) { row.createCell(COL_HOURS).setCellValue(e[COL_HOURS]); }
            }
            row.createCell(COL_TASK).setCellValue(nullSafe(e[COL_TASK]));
            row.createCell(COL_COMPANY).setCellValue(nullSafe(e[COL_COMPANY]));
            row.createCell(COL_SOW).setCellValue(nullSafe(e[COL_SOW]));
        }
        autoSize(sheet);
    }

    /** Name × date pivot matrix with Grand Total and Days columns. */
    private void buildPivot(Sheet sheet, List<String[]> entries, TreeSet<LocalDateTime> dates) {
        // title row mirrors the native Sydney SoftDev pivot layout
        Row title = sheet.createRow(2);
        title.createCell(0).setCellValue("Sum of Hours (Mandatory)");
        title.createCell(1).setCellValue("Date (Mandatory)");

        List<LocalDateTime> sortedDates = new ArrayList<>(dates);
        Map<String, Map<LocalDateTime, Double>> byEmployee = new LinkedHashMap<>();

        for (String[] e : entries) {
            String employee = e[COL_NAME];
            double h = 0;
            try { h = Double.parseDouble(e[COL_HOURS]); } catch (Exception ignored) { }
            LocalDateTime d = java.time.LocalDate.parse(e[COL_DATE]).atStartOfDay();
            byEmployee.computeIfAbsent(employee, k -> new LinkedHashMap<>()).merge(d, h, Double::sum);
        }

        Row header = sheet.createRow(3);
        header.createCell(0).setCellValue("Name (Mandatory)");
        int col = 1;
        for (LocalDateTime d : sortedDates) header.createCell(col++).setCellValue(d);
        int grandTotalCol = col;
        header.createCell(grandTotalCol).setCellValue("Grand Total");
        header.createCell(grandTotalCol + 1).setCellValue("Days");

        int r = 4;
        for (Map.Entry<String, Map<LocalDateTime, Double>> emp : byEmployee.entrySet()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(emp.getKey());
            double total = 0;
            int days = 0;
            int c = 1;
            for (LocalDateTime d : sortedDates) {
                Double h = emp.getValue().get(d);
                if (h != null && h != 0) {
                    row.createCell(c).setCellValue(h);
                    total += h;
                    days++;
                }
                c++;
            }
            row.createCell(grandTotalCol).setCellValue(total);
            row.createCell(grandTotalCol + 1).setCellValue(days);
        }

        Row grandTotalRow = sheet.createRow(r);
        grandTotalRow.createCell(0).setCellValue("Grand Total");
        for (int c = 1; c <= grandTotalCol + 1; c++) {
            double sum = 0;
            boolean any = false;
            for (int rr = 4; rr < r; rr++) {
                org.apache.poi.ss.usermodel.Cell cell = sheet.getRow(rr).getCell(c);
                if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                    sum += cell.getNumericCellValue();
                    any = true;
                }
            }
            if (any) grandTotalRow.createCell(c).setCellValue(sum);
        }
        autoSize(sheet);
    }

    /** Project totals + project hierarchy tables, mirroring the native layout. */
    private void buildProjectWise(Sheet sheet, List<String[]> entries) {
        Map<String, Double> projectTotals = new LinkedHashMap<>();
        double grandTotal = 0;
        for (String[] e : entries) {
            String project = e[COL_PROJECT];
            if (project.isBlank()) continue;
            double h = 0;
            try { h = Double.parseDouble(e[COL_HOURS]); } catch (Exception ignored) { }
            projectTotals.merge(project, h, Double::sum);
            grandTotal += h;
        }

        // Table 1: project totals (cols A:B)
        Row t1Header = sheet.createRow(2);
        t1Header.createCell(0).setCellValue("Project (Mandatory)");
        t1Header.createCell(1).setCellValue("Sum of Hours (Mandatory)");

        // Table 2: project hierarchy (cols D:G), sharing row 2 with table 1 —
        // sub-project/code unknown in generalized layouts, so each project
        // contributes a single context row.
        t1Header.createCell(3).setCellValue("Project (Mandatory)");
        t1Header.createCell(4).setCellValue("Sub Project (if applicable)");
        t1Header.createCell(5).setCellValue("Project Code (Mandatory)");
        t1Header.createCell(6).setCellValue("Sum of Hours (Mandatory)");

        int r = 3;
        // Both tables live side-by-side on shared row indexes (A:B and D:G),
        // exactly like the native Sydney SoftDev layout. Rows are created ONCE
        // — calling createRow twice on the same index would wipe earlier cells.
        for (Map.Entry<String, Double> p : projectTotals.entrySet()) {
            Row row = sheet.createRow(r);
            row.createCell(0).setCellValue(p.getKey());
            row.createCell(1).setCellValue(p.getValue());
            row.createCell(3).setCellValue(p.getKey());
            row.createCell(6).setCellValue(p.getValue());
            r++;
        }
        Row grandRow = sheet.createRow(r);
        grandRow.createCell(0).setCellValue("Grand Total");
        grandRow.createCell(1).setCellValue(grandTotal);
        grandRow.createCell(3).setCellValue("Grand Total");
        grandRow.createCell(6).setCellValue(grandTotal);
        autoSize(sheet);
    }

    // ── Normalization of meta sheets ─────────────────────────────────────────

    /**
     * Copies Summary inserting a blank "Travel Expense" column at index 9 so
     * Total Amount lands at index 10 like the Sydney SoftDev layout.
     */
    private void normalizeSummary(Workbook src, Workbook out) {
        Sheet summary = src.getSheet("Summary");
        if (summary == null) return;
        Sheet target = out.createSheet("Summary");
        for (int r = 0; r <= summary.getLastRowNum(); r++) {
            Row srcRow = summary.getRow(r);
            if (srcRow == null) continue;
            Row outRow = target.createRow(r);
            int maxCol = Math.max(srcRow.getLastCellNum(), 10);
            for (int c = 0; c < maxCol; c++) {
                int destCol = (c >= 9) ? c + 1 : c;      // shift J.. right by one
                copyCell(srcRow.getCell(c), outRow.createCell(destCol));
            }
        }
        // label the inserted column like the native Sydney SoftDev header
        Row header = target.getRow(1);
        if (header != null) {
            Cell inserted = header.getCell(9);
            if (inserted == null) inserted = header.createCell(9);
            if (text(inserted).isBlank()) {
                inserted.setCellValue("Travel Expense\n(USD)");
            }
        }
        autoSize(target);
    }

    /**
     * Copies Commercial dropping leading non-key preamble rows so that
     * "Project Name" lands at row 0 exactly like the Sydney SoftDev layout.
     */
    private void normalizeCommercial(Workbook src, Workbook out) {
        Sheet commercial = src.getSheet("Commercial");
        if (commercial == null) return;
        Sheet target = out.createSheet("Commercial");

        int offset = -1;
        for (int r = 0; r <= commercial.getLastRowNum(); r++) {
            Row row = commercial.getRow(r);
            if (row == null) continue;
            if ("Project Name".equalsIgnoreCase(text(row.getCell(0)).trim())) {
                offset = r;
                break;
            }
        }
        if (offset < 0) offset = 0;

        for (int r = offset; r <= commercial.getLastRowNum(); r++) {
            Row srcRow = commercial.getRow(r);
            if (srcRow == null) continue;
            Row outRow = target.createRow(r - offset);
            for (int c = 0; c < srcRow.getLastCellNum(); c++) {
                copyCell(srcRow.getCell(c), outRow.createCell(c));
            }
        }
        autoSize(target);
    }

    /** Carries the Admin reference sheet over untouched (viewer display only). */
    private void copyAdminSheet(Workbook src, Workbook out) {
        Sheet admin = src.getSheet("Admin");
        if (admin == null) return;
        Sheet target = out.createSheet("Admin");
        for (int r = 0; r <= admin.getLastRowNum(); r++) {
            Row srcRow = admin.getRow(r);
            if (srcRow == null) continue;
            Row outRow = target.createRow(r);
            for (int c = 0; c < srcRow.getLastCellNum(); c++) {
                copyCell(srcRow.getCell(c), outRow.createCell(c));
            }
        }
    }

    // ── Cell helpers ─────────────────────────────────────────────────────────

    private void copyCell(org.apache.poi.ss.usermodel.Cell src,
                          org.apache.poi.ss.usermodel.Cell dst) {
        if (src == null) return;
        switch (src.getCellType()) {
            case NUMERIC -> dst.setCellValue(src.getNumericCellValue());
            case BOOLEAN -> dst.setCellValue(src.getBooleanCellValue());
            case FORMULA -> dst.setCellFormula(src.getCellFormula());
            case BLANK -> { }
            default -> dst.setCellValue(src.getStringCellValue());
        }
        if (src.getCellStyle() != null && src.getSheet().getWorkbook() == dst.getSheet().getWorkbook()) {
            dst.setCellStyle(src.getCellStyle());
        }
    }

    private void autoSize(Sheet sheet) {
        Row first = sheet.getRow(0);
        int cols = first != null ? first.getLastCellNum() : 0;
        // width kept modest so wide pivots stay readable in the viewer
        for (int c = 0; c < cols; c++) {
            sheet.setColumnWidth(c, 12 * 256);
        }
    }

    private static String text(org.apache.poi.ss.usermodel.Cell cell) {
        return text(cell, null);
    }

    private static String text(org.apache.poi.ss.usermodel.Cell cell,
                               org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try {
            switch (effectiveType(cell, evaluator)) {
                case NUMERIC: {
                    double d = numericValue(cell, evaluator);
                    return (d == Math.floor(d) && !Double.isInfinite(d))
                            ? String.valueOf((long) d) : String.valueOf(d);
                }
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                case STRING:
                    return stringValue(cell, evaluator).trim();
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String numeric(org.apache.poi.ss.usermodel.Cell cell,
                                  org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell == null) return "";
        if (effectiveType(cell, evaluator) == CellType.NUMERIC) {
            double d = numericValue(cell, evaluator);
            return (d == Math.floor(d) && !Double.isInfinite(d))
                    ? String.valueOf((long) d) : String.valueOf(d);
        }
        String s = text(cell, evaluator);
        return s.isBlank() || "NA".equalsIgnoreCase(s.trim()) ? "" : s.trim();
    }

    /** Resolves FORMULA cells to their cached/evaluated effective type. */
    private static CellType effectiveType(org.apache.poi.ss.usermodel.Cell cell,
                                          org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell.getCellType() != CellType.FORMULA) {
            return cell.getCellType();
        }
        if (evaluator != null) {
            try {
                CellValue cv = evaluator.evaluate(cell);
                return cv != null ? cv.getCellType() : CellType.BLANK;
            } catch (Exception ignored) { }
        }
        try { return cell.getCachedFormulaResultType(); }
        catch (Exception ignored) { return CellType.BLANK; }
    }

    private static double numericValue(org.apache.poi.ss.usermodel.Cell cell,
                                       org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.FORMULA) {
            if (evaluator != null) {
                try {
                    CellValue cv = evaluator.evaluate(cell);
                    if (cv != null && cv.getCellType() == CellType.NUMERIC) return cv.getNumberValue();
                } catch (Exception ignored) { }
            }
            return cell.getNumericCellValue(); // fall back to cached result
        }
        return cell.getNumericCellValue();
    }

    private static String stringValue(org.apache.poi.ss.usermodel.Cell cell,
                                      org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.FORMULA) {
            if (evaluator != null) {
                try {
                    CellValue cv = evaluator.evaluate(cell);
                    if (cv != null && cv.getCellType() == CellType.STRING) return cv.getStringValue();
                } catch (Exception ignored) { }
            }
            return cell.getStringCellValue(); // fall back to cached result
        }
        return cell.getStringCellValue();
    }

    private static String firstNonBlank(String a, String b) {
        return !a.isBlank() ? a : b;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
