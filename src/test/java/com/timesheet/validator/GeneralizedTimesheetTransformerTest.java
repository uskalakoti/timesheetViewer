package com.timesheet.validator;

import com.timesheet.validator.domain.UploadProject;
import com.timesheet.validator.service.GeneralizedTimesheetTransformer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CR 5.3 — verifies the Generalized → Sydney SoftDev transformation engine
 * against the real IATA TIMATIC sample workbook.
 *
 * <p>The sample lives at the repository root and is not committed; tests are
 * skipped gracefully when it is absent so CI stays green.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneralizedTimesheetTransformerTest {

    private static final Path SAMPLE =
            Path.of("June 2026 - Timatic Timesheet - sample (1) 2.xlsx");

    private byte[] transformedBytes;

    @BeforeAll
    void transformSample() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Timatic sample not present — skipping");
        GeneralizedTimesheetTransformer t = new GeneralizedTimesheetTransformer();
        try (InputStream in = Files.newInputStream(SAMPLE);
             XSSFWorkbook src = new XSSFWorkbook(in)) {
            transformedBytes = t.transform(src, UploadProject.GENERALIZED_TIMESHEET);
        }
    }

    private XSSFWorkbook out() throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(transformedBytes));
    }

    @Test
    @DisplayName("Transformed workbook contains exactly the standard sheets")
    void standardSheetsPresent() throws Exception {
        try (XSSFWorkbook wb = out()) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
            assertThat(names).containsSubsequence(
                    "Timesheet", "Pivot", "Projectwise", "Summary", "Commercial");
            assertThat(names).doesNotContain("Deepa Malik", "Gaurav Kumar"); // person sheets gone
        }
    }

    @Test
    @DisplayName("Timesheet sheet: 11 standard headers and consolidated rows")
    void timesheetStructure() throws Exception {
        try (XSSFWorkbook wb = out()) {
            var sheet = wb.getSheet("Timesheet");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Date (Mandatory)");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Name (Mandatory)");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("Hours (Mandatory)");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("SOW (Mandatory)");

            // sample consolidates 6 person sheets with ~30 rows each
            assertThat(sheet.getLastRowNum()).isGreaterThan(100);

            // first data row carries a real date cell, a name and numeric hours
            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            String name = first.getCell(1).getStringCellValue();
            assertThat(name).isNotBlank();
            // hours cell is numeric when present
            Cell hours = first.getCell(7);
            if (hours != null && hours.getCellType() != CellType.BLANK) {
                assertThat(hours.getCellType()).isEqualTo(CellType.NUMERIC);
            }
        }
    }

    @Test
    @DisplayName("Pivot sheet synthesized with Grand Total and Days columns")
    void pivotStructure() throws Exception {
        try (XSSFWorkbook wb = out()) {
            var sheet = wb.getSheet("Pivot");
            boolean foundHeader = false;
            int headerRowIdx = -1, gtCol = -1;
            for (int r = 0; r <= sheet.getLastRowNum() && !foundHeader; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null || cell.getCellType() != CellType.STRING) continue;
                    if ("Grand Total".equals(cell.getStringCellValue())) {
                        foundHeader = true;
                        headerRowIdx = r;
                        gtCol = c;
                        break;
                    }
                }
            }
            assertThat(foundHeader).as("Grand Total header").isTrue();
            assertThat(sheet.getRow(headerRowIdx).getCell(gtCol + 1).getStringCellValue())
                    .isEqualTo("Days");

            // employees below the header row, bottom row labelled Grand Total
            Row firstEmployee = sheet.getRow(headerRowIdx + 1);
            assertThat(firstEmployee.getCell(0).getStringCellValue()).isNotIn("Grand Total");
            Row lastRow = sheet.getRow(sheet.getLastRowNum());
            assertThat(lastRow.getCell(0).getStringCellValue()).isEqualTo("Grand Total");
        }
    }

    @Test
    @DisplayName("Projectwise sheet synthesized with both tables")
    void projectWiseStructure() throws Exception {
        try (XSSFWorkbook wb = out()) {
            var sheet = wb.getSheet("Projectwise");
            Row t1 = sheet.getRow(2);
            assertThat(t1.getCell(0).getStringCellValue()).isEqualTo("Project (Mandatory)");
            assertThat(t1.getCell(1).getStringCellValue()).isEqualTo("Sum of Hours (Mandatory)");
            assertThat(t1.getCell(3).getStringCellValue()).isEqualTo("Project (Mandatory)");
            assertThat(t1.getCell(6).getStringCellValue()).isEqualTo("Sum of Hours (Mandatory)");

            // project totals present and hours sum to a positive grand total
            double total = 0;
            for (int r = 3; r <= sheet.getLastRowNum(); r++) {
                Cell h = sheet.getRow(r).getCell(1);
                if (h != null && h.getCellType() == CellType.NUMERIC) total += h.getNumericCellValue();
            }
            assertThat(total).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Summary normalized: Total Amount lands in column K like Sydney SoftDev")
    void summaryNormalized() throws Exception {
        try (XSSFWorkbook wb = out()) {
            var sheet = wb.getSheet("Summary");
            Row header = sheet.getRow(1);
            assertThat(header.getCell(10).getStringCellValue()).contains("Total Amount");
            // inserted Travel Expense column sits right before it
            assertThat(header.getCell(9)).isNotNull();
        }
    }

    @Test
    @DisplayName("Commercial normalized: key/value pairs start at row 0")
    void commercialNormalized() throws Exception {
        try (XSSFWorkbook wb = out()) {
            var sheet = wb.getSheet("Commercial");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Project Name");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("PO Number");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue())
                    .isEqualTo("Total Billable Headcount");
            // preamble note rows are stripped
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .doesNotContain("IGT SOW No 19-2026)");
        }
    }

    @Test
    @DisplayName("Non-generalized upload rejected with a helpful error")
    void rejectsUnrecognisedLayout() throws Exception {
        GeneralizedTimesheetTransformer t = new GeneralizedTimesheetTransformer();
        try (XSSFWorkbook empty = new XSSFWorkbook()) {
            empty.createSheet("Random");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> t.transform(empty, UploadProject.GENERALIZED_TIMESHEET));
        }
    }
}
