package com.timesheet.validator.service;

import com.timesheet.validator.config.RuleCatalog;
import com.timesheet.validator.domain.UploadSession;
import com.timesheet.validator.repository.UploadSessionRepository;
import com.timesheet.validator.config.AppProperties;
import com.timesheet.validator.domain.CellData;
import com.timesheet.validator.domain.ValidationIssue;
import com.timesheet.validator.dto.ValidationResultDto;
import com.timesheet.validator.dto.ValidationResultDto.IssueDto;
import com.timesheet.validator.model.CellReference;
import com.timesheet.validator.model.ProjectCodeKey;
import com.timesheet.validator.model.ProjectKey;
import com.timesheet.validator.model.ProjectSummary;
import com.timesheet.validator.model.ProjectWiseHierarchy;
import com.timesheet.validator.model.SubProjectKey;
import com.timesheet.validator.model.SubProjectSummary;
import com.timesheet.validator.service.ProjectWiseParser;
import com.timesheet.validator.model.ProjectCodeSummary;
import com.timesheet.validator.repository.CellDataRepository;
import com.timesheet.validator.repository.PublicHolidayRepository;
import com.timesheet.validator.domain.Resource;
import com.timesheet.validator.repository.ResourceRepository;
import com.timesheet.validator.repository.ResourceSowRepository;
import com.timesheet.validator.repository.SowMasterRepository;
import com.timesheet.validator.repository.ValidationIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates the Timesheet sheet of an uploaded workbook against:
 *   TS-01  Max 8 hours per resource per date
 *   TS-02  No weekend entries
 *   TS-03  No public holiday entries
 *   TS-04  Hours must be positive
 *   TS-05  Resource name must exist in roster
 *   TS-06  SOW must match expected value
 *   TS-07  Date must be within billing period for that resource
 *
 * Column mapping (0-based) for sheet "Timesheet":
 *   0=Date  1=Name  2=Team  3=Project  4=SubProject  5=ProjectCode
 *   6=Country  7=Hours  8=Task  9=Company  10=SOW
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationService {

    private static final String SHEET = "Timesheet";
    private static final String PIVOT_SHEET = "Pivot";
//    private static final double HOURS_PER_DAY = 8.0;
    private static final String PROJECT_WISE_SHEET = "Projectwise";
    private static final String SUMMARY_SHEET = "Summary";
    private static final String COMMERCIAL_SHEET = "Commercial";
    
    private final ProjectWiseParser projectWiseParser;

    private final ResourceRepository resourceRepo;

    //additon of new code
    private Map<String, Integer> pivotEmployeeRows =
            new HashMap<>();
    private Map<String, Integer> pivotDateColumns =
            new HashMap<>();

    // Supported date formats in the Excel file
    private static final List<DateTimeFormatter> DATE_FMTS = List.of(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),                    // ISO — rawValue (primary)
        DateTimeFormatter.ofPattern("dd-MMM-yy",   Locale.ENGLISH),  // 01-Mar-26
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),  // 01-Mar-2026
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),                    // 01/03/2026
        DateTimeFormatter.ofPattern("M/d/yy"),                        // 3/1/26
        DateTimeFormatter.ofPattern("dd-MM-yyyy")                     // 01-03-2026
    );

    private final AppProperties props;
    private final CellDataRepository cellRepo;
    private final PublicHolidayRepository holidayRepo;
    private final ResourceSowRepository resourceSowRepo;
    private final SowMasterRepository sowMasterRepo;
    private final ValidationIssueRepository issueRepo;
    private final UploadSessionRepository sessionRepo;
    private final RuleCatalog ruleCatalog;

    @Transactional
    public ValidationResultDto validate(String sessionId) {

        log.info("VALIDATION STARTED FOR SESSION {}", sessionId);

        issueRepo.deleteBySessionId(sessionId);

        UploadSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        Set<String> enabledRules = new HashSet<>();

        if (session.getEnabledRules() != null &&
                !session.getEnabledRules().isBlank()) {

            enabledRules.addAll(
                    Arrays.stream(session.getEnabledRules().split(","))
                            .map(String::trim)
                            .collect(Collectors.toSet())
            );
        }

//        log.info("ENABLED RULES = {}", enabledRules);

        List<CellData> allCells = cellRepo
            .findBySessionIdAndSheetNameOrderByRowIdxAscColIdxAsc(sessionId, SHEET);


//        log.info("=========== TIMESHEET HEADER DEBUG ===========");
//
//        for (CellData cell : allCells) {
//
//            if (cell.getRowIdx() == 0) {
//
//                log.info(
//                        "TIMESHEET HEADER col={} value={}",
//                        cell.getColIdx(),
//                        cell.getDisplayValue()
//                );
//            }
//        }
//
//        log.info("==============================================");


        List<CellData> pivotCells =
                cellRepo.findBySessionIdAndSheetNameOrderByRowIdxAscColIdxAsc(
                        sessionId,
                        PIVOT_SHEET
                );


        List<CellData> projectWiseCells =
        cellRepo.findBySessionIdAndSheetNameOrderByRowIdxAscColIdxAsc(
                sessionId,
                PROJECT_WISE_SHEET);        


//        log.info("=========== PIVOT  ROW = 3 DEBUG ===========");
//
//        for (CellData cell : pivotCells) {
//
//            if (cell.getRowIdx() == 3) {
//
//                log.info(
//                        "PIVOT HEADER col={} value={}",
//                        cell.getColIdx(),
//                        cell.getDisplayValue()
//                );
//            }
//        }
//
//        log.info("===========================================");


        if (allCells.isEmpty()) {
            log.warn("[Validation] No cells found for sheet '{}' session={}", SHEET, sessionId);
            return ValidationResultDto.builder()
                .sessionId(sessionId).passed(true)
                .errors(List.of()).warnings(List.of()).build();
        }

        // Build a row-map: rowIdx → (colIdx → CellData)
        TreeMap<Integer, Map<Integer, CellData>> rowMap = new TreeMap<>();
        for (CellData c : allCells) {
            rowMap.computeIfAbsent(c.getRowIdx(), k -> new TreeMap<>()).put(c.getColIdx(), c);
        }

        int headerRow = rowMap.firstKey();
        Set<String> knownNames = resourceRepo.findAll().stream()
            .map(r -> r.getName().trim().toLowerCase())
            .collect(Collectors.toSet());
        Set<LocalDate> holidays = holidayRepo.findAll().stream()
            .filter(h -> h.isActive())
            .map(h -> h.getHolidayDate())
            .collect(Collectors.toSet());
        String expectedSow = props.getValidation().getExpectedSow();
        double maxHours = props.getValidation().getMaxHoursPerDay();

        List<ValidationIssue> issues = new ArrayList<>();


        // Phase gate: pivot reconciliation runs only once the Timesheet phase
        // has passed. In TIMESHEET phase we emit Timesheet (TS-xx) issues only.
        boolean pivotPhase = "PIVOT".equalsIgnoreCase(session.getValidationPhase());

        boolean projectWisePhase =
        "PROJECT_WISE".equalsIgnoreCase(
                session.getValidationPhase());

        boolean summaryPhase =
        "SUMMARY".equalsIgnoreCase(
                session.getValidationPhase());

        boolean commercialPhase =
        "COMMERCIAL".equalsIgnoreCase(
                session.getValidationPhase());

        if (!pivotCells.isEmpty() && pivotPhase) {

            int pivotGtRow = pivotGrandTotalRow(pivotCells, 21);

            Set<String> timesheetEmployees =
                    extractTimesheetEmployees(allCells);

            Set<String> pivotEmployees =
                    extractPivotEmployees(pivotCells);

            Set<String> missingPivotEmployees =
                    new HashSet<>(timesheetEmployees);

            missingPivotEmployees.removeAll(pivotEmployees);

            Map<String, Double> timesheetTotals =
                    extractTimesheetEmployeeTotals(allCells);

            Map<String, Double> pivotTotals =
                    extractPivotEmployeeTotals(pivotCells);

            log.info("TIMESHEET TOTALS = {}", timesheetTotals);
            log.info("PIVOT TOTALS = {}", pivotTotals);


            Map<String, Double> timesheetDateTotals =
                    extractTimesheetDateTotals(allCells);

            Map<String, Double> pivotDateTotals =
                    extractPivotDateTotals(pivotCells);

            log.info("TIMESHEET DATE TOTALS={}",
                    timesheetDateTotals);

            log.info("PIVOT DATE TOTALS={}",
                    pivotDateTotals);


//            log.info("TIMESHEET EMPLOYEES = {}", timesheetEmployees);
//            log.info("PIVOT EMPLOYEES = {}", pivotEmployees);


            PivotLayout layout = findPivotLayout(pivotCells);

            int grandTotalColumn = layout.getGrandTotalColumn();
            int workingDaysColumn = grandTotalColumn + 1;


            // =========================
            // PS-01 Resource Validation
            // =========================

            for (String employee
                    : timesheetEmployees) {

                if (!pivotEmployees.contains(employee)) {

                    issues.add(
                            pivotIssue(
                                    sessionId,
                                    "PS-01",
                                    "CRITICAL",
                                    -1,
                                    0,
                                    "Employee Name",
                                    "Resource '" +employee+  "' missing in Pivot sheet. " +
                                            "Please verify employee list for the project. "
                            )
                    );
                }
            }


            // =========================
            // PS-02 Hours Validation
            // =========================

            Map<String, Integer> pivotEmployeeRows =
                    extractPivotEmployeeRows(pivotCells);



            for (Map.Entry<String, Double> entry
                    : timesheetTotals.entrySet()) {

                String employee =
                        entry.getKey();

                Double timesheetHours =
                        entry.getValue();

                Double pivotHours =
                        pivotTotals.get(employee);

                if (pivotHours == null) {
                    continue;
                }

                if (Math.abs(timesheetHours - pivotHours) > 0.01) {

                    issues.add(
                            pivotIssue(
                                    sessionId,
                                    "PS-02",
                                    "CRITICAL",
                                    pivotEmployeeRows.getOrDefault(employee, -1),
                                    grandTotalColumn, // Grand Total column
                                    "Grand Total",
                                    String.format(
                                            "Total Hours Calculation wrong for %s. Please Check Entries. Timesheet=%.1f Pivot=%.1f",
                                            employee,
                                            timesheetHours,
                                            pivotHours
                                    )
                            )
                    );
                }
            }

            // =========================
            // PS-03 Date wise hours calculation validation
            // =========================

            Map<String, Integer> pivotDateColumns =
                    extractPivotDateColumns(pivotCells);

            for (Map.Entry<String, Double> entry
                    : timesheetDateTotals.entrySet()) {

                String date = entry.getKey();

                Double timesheetHours =
                        entry.getValue();

                Double pivotHours =
                        pivotDateTotals.get(date);

                if (pivotHours == null) {
                    continue;
                }

                if (Math.abs(timesheetHours - pivotHours) > 0.01) {

//                    issues.add(
//                            pivotIssue(
//                                    sessionId,
//                                    "PS-03",
//                                    "CRITICAL",
//                                    "Date-wise Total",
//                                    String.format(
//                                            "%s Total Hours Calculation wrong. Timesheet=%.1f Pivot=%.1f",
//                                            date,
//                                            timesheetHours,
//                                            pivotHours
//                                    )
//                            )
//                    );


                    Integer col = pivotDateColumns.get(date);

                    if (col != null) {

                        issues.add(
                                pivotIssue(
                                        sessionId,
                                        "PS-03",
                                        "CRITICAL",
                                        pivotGtRow,
                                        col,
                                        "Date-wise Total",
                                        String.format(
                                                "%s Total Hours Calculation wrong. Timesheet=%.1f Pivot=%.1f",
                                                date,
                                                timesheetHours,
                                                pivotHours
                                        )
                                )
                        );
                    }

                }
            }


            //PS-04



            Map<String, Double> timesheetEmployeeDateTotals =
                    extractTimesheetEmployeeDateTotals(allCells);

            Map<String, Double> pivotEmployeeDateTotals =
                    extractPivotEmployeeDateTotals(pivotCells);

//            log.info(
//                    "TIMESHEET EMPLOYEE DATE TOTALS={}",
//                    timesheetEmployeeDateTotals);
//
//            log.info(
//                    "PIVOT EMPLOYEE DATE TOTALS={}",
//                    pivotEmployeeDateTotals);

            for (Map.Entry<String, Double> entry
                    : timesheetEmployeeDateTotals.entrySet()) {

                String employeeDate =
                        entry.getKey();

                Double timesheetHours =
                        entry.getValue();

                Double pivotHours =
                        pivotEmployeeDateTotals.get(employeeDate);

                if (pivotHours == null) {
                    pivotHours = 0.0;
                }

                if (Math.abs(timesheetHours - pivotHours) > 0.01) {

                    String[] parts =
                            employeeDate.split("\\|");

                    String employee =
                            parts[0];

                    if (missingPivotEmployees.contains(employee)) {
                        continue;
                    }

                    String date =
                            parts[1];

//                    issues.add(
//                            pivotIssue(
//                                    sessionId,
//                                    "PS-04",
//                                    "CRITICAL",
//                                    pivotEmployeeRows.getOrDefault(employee, -1),
//                                    -1,
//                                    "Employee-Date Validation",
//                                    String.format(
//                                            "%s has incorrect hours on %s. Timesheet=%.1f Pivot=%.1f",
//                                            employee,
//                                            date,
//                                            timesheetHours,
//                                            pivotHours
//                                    )
//                            )
//                    );



                    Integer row =
                            pivotEmployeeRows.getOrDefault(
                                    employee,
                                    -1
                            );

                    Integer col =
                            pivotDateColumns.getOrDefault(
                                    date,
                                    -1
                            );

//                    System.out.println("######LLLLoggggss#######");
//                    log.info(
//                            "PS04 -> employee={} row={} date={} col={}",
//                            employee,
//                            row,
//                            date,
//                            col
//                    );

//                    log.info(
//                            "PS04 ISSUE -> row={} col={} employee={} date={}",
//                            row,
//                            col,
//                            employee,
//                            date
//                    );



                    String validationMessage =
                            String.format(
                                    "%s has incorrect hours on %s. Timesheet=%.1f Pivot=%.1f",
                                    employee,
                                    date,
                                    timesheetHours,
                                    pivotHours
                            );


                    issues.add(
                            pivotIssue(
                                    sessionId,
                                    "PS-04",
                                    "CRITICAL",
                                    row,
                                    col,
                                    "Employee-Date Validation",
                                    validationMessage
                            )
                    );

                    if (row >= 0) {

                        issues.add(
                                pivotIssue(
                                        sessionId,
                                        "PS-04",
                                        "CRITICAL",
                                        row,
                                        0,
                                        "Employee",
                                        validationMessage
                                )
                        );
                    }

                }
            }

            //PS-05
            Double pivotGrandTotal = extractPivotGrandTotal(pivotCells);

            Double calculatedTotal = calculatePivotDateColumnTotal(pivotCells);


//            log.info(
//                    "FR3 CHECK -> Calculated={} Pivot={}",
//                    calculatedTotal,
//                    pivotGrandTotal);


            if (pivotGrandTotal != null
                    && Math.abs(
                    pivotGrandTotal
                            - calculatedTotal) > 0.01) {

                issues.add(

                        pivotIssue(
                                sessionId,
                                "PS-05",
                                "CRITICAL",
                                pivotGtRow,
                                grandTotalColumn,
                                "Pivot Grand Total",
                                String.format(
                                        "Pivot Grand Total calculation incorrect. Expected=%.1f Actual=%.1f",
                                        calculatedTotal,
                                        pivotGrandTotal
                                )
                        )

//                        pivotIssue(
//                                sessionId,
//                                "PS-05",
//                                "CRITICAL",
//                                "Pivot Grand Total",
//                                String.format(
//                                        "Pivot Grand Total calculation incorrect. Expected=%.1f Actual=%.1f",
//                                        calculatedTotal,
//                                        pivotGrandTotal
//                                )
//                        )
                );
            }


            //PS-06

            Map<String, Double> pivotDays =
                    extractPivotEmployeeDays(pivotCells);

            Map<String, Double> workingHoursMap =
                    resourceRepo.findAll()
                            .stream()
                            .collect(Collectors.toMap(
                                    r -> normalizeName(r.getName()),
                                    r -> r.getWorkingHoursPerDay() != null
                                            ? r.getWorkingHoursPerDay()
                                            : props.getDefaultWorkingHoursPerDay()
                            ));




//            log.info("PIVOT DAYS = {}", pivotDays);


            for (Map.Entry<String, Double> entry
                    : pivotTotals.entrySet()) {

                String employee =
                        entry.getKey();

                Double totalHours =
                        entry.getValue();

                Double actualDays =
                        pivotDays.get(employee);

                if (actualDays == null) {
                    continue;
                }

                double workingHoursPerDay =
                        workingHoursMap.getOrDefault(
                                employee,
                                props.getDefaultWorkingHoursPerDay()
                        );

                double expectedDays =
                        totalHours / workingHoursPerDay;


                log.info(
                        "PS-06 -> employee={} totalHours={} workingHoursPerDay={} expectedDays={} actualDays={}",
                        employee,
                        totalHours,
                        workingHoursPerDay,
                        expectedDays,
                        actualDays
                );


                if (Math.abs(
                        expectedDays - actualDays) > 0.01) {


                    int row = pivotEmployeeRows.getOrDefault(employee, -1);

                    String message =
                            String.format(
                                    "Working days calculation mismatch with pivot excel. Please review calculations. Employee: %s Expected: %.1f Actual: %.1f",
                                    employee,
                                    expectedDays,
                                    actualDays
                            );

// Employee name cell
                    issues.add(
                            pivotIssue(
                                    sessionId,
                                    "PS-06",
                                    "CRITICAL",
                                    row,
                                    0,
                                    "Working Days",
                                    message

                            )
                    );

// Working Days cell
                    issues.add(
                            pivotIssue(
                                    sessionId,
                                    "PS-06",
                                    "CRITICAL",
                                    row,
                                    workingDaysColumn,
                                    "Working Days",
                                    message
                            )
                    );

                }
            }


        }




        // ── Aggregate daily hours per (name, date) for TS-01 ─────────────────

        // ======================================================
        // PROJECT WISE VALIDATION
        // ======================================================

        if (projectWisePhase && !projectWiseCells.isEmpty()) {

        validateProjectWise(
                sessionId,
                allCells,
                projectWiseCells,
                issues);
        }

        // ======================================================
        // SUMMARY VALIDATION
        // ======================================================

        // Summary cells are needed by BOTH the Summary phase and the Commercial
        // phase (CM-02 headcount, CM-03 billable days, CM-04 billable amount all
        // cross-reference the Summary sheet). Load them whenever either phase is
        // active so the Commercial checks are not silently skipped.
        List<CellData> summaryCells = new ArrayList<>();
        if (summaryPhase || commercialPhase) {

            summaryCells =
                    cellRepo.findBySessionIdAndSheetNameOrderByRowIdxAscColIdxAsc(
                            sessionId,
                            SUMMARY_SHEET);
        }

        if (summaryPhase && !summaryCells.isEmpty()) {

            validateSummary(
                    sessionId,
                    allCells,
                    pivotCells,
                    summaryCells,
                    issues);
        }

        // ======================================================
        // COMMERCIAL VALIDATION
        // ======================================================

        if (commercialPhase) {

            List<CellData> commercialCells =
                    cellRepo.findBySessionIdAndSheetNameOrderByRowIdxAscColIdxAsc(
                            sessionId,
                            COMMERCIAL_SHEET);

            if (!commercialCells.isEmpty()) {

                validateCommercial(
                        sessionId,
                        allCells,
                        summaryCells,
                        commercialCells,
                        issues);
            }
        }
        Map<String, Map<LocalDate, Double>> dailyHours = new HashMap<>();

        for (Map.Entry<Integer, Map<Integer, CellData>> rowEntry : rowMap.entrySet()) {
            int ri = rowEntry.getKey();
            if (ri == headerRow) continue; // skip header
            Map<Integer, CellData> cols = rowEntry.getValue();

//            String rawDate  = valRaw(cols, 0); // rawValue = ISO yyyy-MM-dd, reliable for parseDate()
//            String name     = val(cols, 1);
//            String hoursStr = val(cols, 7);
//            String sow      = val(cols, 10);
//            String country  = val(cols, 6);


//            String rawDate  =
            String rawDate = valRaw(cols, 0);
            String name     = val(cols, 1);
            String assignedTeam     = val(cols, 2);
            String project  = val(cols, 3);
            String subProject = val(cols, 4);
            String projectCode = val(cols, 5);
            String country  = val(cols, 6);
            String hoursStr = val(cols, 7);
            String task     = val(cols, 8);
            String company  = val(cols, 9);
            String sow      = val(cols, 10);


            if (rawDate.isBlank() && name.isBlank()) continue; // blank row


            String[] fieldNames = {
                    "Date",
                    "Name",
                    "Assigned Team",
                    "Project",
                    "Sub Project",
                    "Project Code",
                    "Country",
                    "Hours",
                    "Task",
                    "Company",
                    "SOW"
            };

            if (isRuleEnabled(enabledRules, "TS-08")) {

                for (int col = 0; col < fieldNames.length; col++) {

                    String value = val(cols, col);

                    if (value.isBlank()) {

                        issues.add(issue(
                                sessionId,
                                "TS-08",
                                "CRITICAL",
                                ri,
                                col,
                                fieldNames[col],
                                fieldNames[col] +
                                        " is mandatory and cannot be blank for resource '" +
                                        name + "'"
                        ));
                    }
                }
            }



            LocalDate date = parseDate(rawDate);

//            System.out.println(
//                    "Row=" + ri +
//                            " RawDate=" + rawDate +
//                            " ParsedDate=" + date
//            );

            // TS-02: Weekend
            if (isRuleEnabled(enabledRules, "TS-02")
                    && date != null
                    && !props.getValidation().isAllowWeekendOverride()) {

//                System.out.println(
//                        "Checking weekend for " +
//                                date +
//                                " Day=" +
//                                date.getDayOfWeek()
//                );

                DayOfWeek dow = date.getDayOfWeek();



                if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {

//                    System.out.println("WEEKEND FOUND");

                    issues.add(issue(sessionId,"TS-02","CRITICAL",ri,0,"Date",
                        "Weekend entry not allowed: " + rawDate + " (" + dow + ") for resource '" + name + "'"));
                }
            }

            // TS-03: Public holiday
            if (isRuleEnabled(enabledRules, "TS-03")
                    && date != null
                    && holidays.contains(date)) {
                String hName = holidayRepo.findAll().stream()
                    .filter(h -> h.getHolidayDate().equals(date))
                    .map(h -> h.getHolidayName()).findFirst().orElse("holiday");
                issues.add(issue(sessionId,"TS-03","CRITICAL",ri,0,"Date",
                    "Entry on public holiday '" + hName + "': " + rawDate + " for resource '" + name + "'"));
            }

            // TS-04: Hours positive
            double hours = 0;

            if (!hoursStr.isBlank()) {

                try {
                    hours = Double.parseDouble(hoursStr.trim());

                    if (isRuleEnabled(enabledRules, "TS-04")
                            && hours <= 0) {

                        issues.add(issue(
                                sessionId,
                                "TS-04",
                                "CRITICAL",
                                ri,
                                7,
                                "Hours",
                                "Hours must be positive, got: " + hoursStr +
                                        " for resource '" + name + "'"
                        ));
                    }

                } catch (NumberFormatException e) {

                    if (isRuleEnabled(enabledRules, "TS-04")) {

                        issues.add(issue(
                                sessionId,
                                "TS-04",
                                "CRITICAL",
                                ri,
                                7,
                                "Hours",
                                "Invalid hours value: '" + hoursStr + "'"
                        ));
                    }
                }
            }

            // TS-05: Known resource
            if (isRuleEnabled(enabledRules, "TS-05")
                    && !name.isBlank()
                    && !knownNames.contains(name.trim().toLowerCase())) {
                issues.add(issue(sessionId,"TS-05","WARNING",ri,1,"Name",
                    "Resource '" + name + "' not found in roster"));
            }

            // TS-06: SOW match
            if (isRuleEnabled(enabledRules, "TS-06")
                    && !sow.isBlank()
                    && !sow.trim().equals(expectedSow)) {
                issues.add(issue(sessionId,"TS-06","CRITICAL",ri,10,"SOW",
                    "SOW mismatch: found '" + sow + "', expected '" + expectedSow + "'" + " for resource '" + name + "'"));
            }

            // TS-07: Date within resource engagement period
            if (isRuleEnabled(enabledRules, "TS-07")
                    && date != null
                    && !name.isBlank()) {
                resourceRepo.findByName(name.trim()).ifPresent(res -> {
                    if (res.getStartDate() != null && date.isBefore(res.getStartDate())) {
                        issues.add(issue(sessionId,"TS-07","WARNING",ri,0,"Date",
                            "Date " + date + " is before engagement start (" + res.getStartDate() + ") for '" + name + "'"));
                    }
                    if (res.getEndDate() != null && date.isAfter(res.getEndDate())) {
                        issues.add(issue(sessionId,"TS-07","WARNING",ri,0,"Date",
                            "Date " + date + " is after engagement end (" + res.getEndDate() + ") for '" + name + "'"));
                    }
                });
            }

            // Accumulate for TS-01
            if (date != null && !name.isBlank() && hours > 0) {
                dailyHours
                    .computeIfAbsent(name.trim().toLowerCase(), k -> new HashMap<>())
                    .merge(date, hours, Double::sum);
            }
        }

        // TS-01: Max hours per day — checked after full scan
        if (isRuleEnabled(enabledRules, "TS-01")) {
            dailyHours.forEach((name, dateMap) ->
                    dateMap.forEach((date, total) -> {
                                if (total != maxHours) {
                                    issues.add(issue(sessionId, "TS-01", "CRITICAL", -1, 7, "Hours",
                                            String.format("Resource '%s' logged %.1f hrs on %s (max %.0f hrs/day)", name, total, date, maxHours)));
                                }
                            })
            );
        };
//        }));

        // Global enable/disable gate (DB-driven): drop any issue whose rule is
        // turned off in RULE_CONFIG. Covers both Timesheet (TS-xx) and Pivot
        // (PS-xx) rules without touching individual emission sites. Rules not
        // managed by the catalog are left untouched.
        issues.removeIf(i -> i.getRuleId() != null
                && !ruleCatalog.isGloballyEnabled(i.getRuleId()));

        issueRepo.saveAll(issues);
        log.info("[Validation] session={} issues={}", sessionId, issues.size());

        List<IssueDto> errors = toDto(issues.stream()
            .filter(i -> "CRITICAL".equals(i.getSeverity())).collect(Collectors.toList()));
        List<IssueDto> warnings = toDto(issues.stream()
            .filter(i -> "WARNING".equals(i.getSeverity())).collect(Collectors.toList()));

        return ValidationResultDto.builder()
            .sessionId(sessionId)
            .passed(errors.isEmpty())
            .errorCount(errors.size())
            .warningCount(warnings.size())
            .errors(errors)
            .warnings(warnings)
            .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String val(Map<Integer, CellData> cols, int col) {
        CellData c = cols.get(col);
        return (c == null || c.getDisplayValue() == null) ? "" : c.getDisplayValue().trim();
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        for (DateTimeFormatter fmt : DATE_FMTS) {
            try { return LocalDate.parse(s.trim(), fmt); } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    /**
     * Applies the (optional) admin-configured message template for a rule.
     * Supported placeholders: {detail} (the engine-computed message),
     * {ruleId}, {severity}, {field}. If no template is configured the
     * engine-computed message is returned unchanged.
     */
    private String renderMessage(String ruleId, String severity, String field, String detail) {
        String tpl = ruleCatalog.getMessageTemplate(ruleId);
        if (tpl == null || tpl.isBlank()) return detail;
        return tpl
                .replace("{detail}", detail == null ? "" : detail)
                .replace("{ruleId}", ruleId == null ? "" : ruleId)
                .replace("{severity}", severity == null ? "" : severity)
                .replace("{field}", field == null ? "" : field);
    }

    private ValidationIssue issue(String sid, String ruleId, String severity,
                                  int row, int col, String field, String msg) {
        return ValidationIssue.builder()
            .sessionId(sid).ruleId(ruleId).severity(severity)
            .sheetName(SHEET).rowIdx(row).colIdx(col)
            .fieldName(field).message(renderMessage(ruleId, severity, field, msg)).build();
    }

    private ValidationIssue pivotIssue(
            String sid,
            String ruleId,
            String severity,
            int row,
            int col,
            String field,
            String msg) {

        return ValidationIssue.builder()
                .sessionId(sid)
                .ruleId(ruleId)
                .severity(severity)
                .sheetName("Pivot")
                .rowIdx(row)
                .colIdx(col)
                .fieldName(field)
                .message(renderMessage(ruleId, severity, field, msg))
                .build();
    }

    private ValidationIssue pivotIssue(
            String sid,
            String ruleId,
            String severity,
            String field,
            String msg
            ) {

        return ValidationIssue.builder()
                .sessionId(sid)
                .ruleId(ruleId)
                .severity(severity)
                .sheetName("Pivot")
                .fieldName(field)
                .message(renderMessage(ruleId, severity, field, msg))
                .build();
    }


    private List<IssueDto> toDto(List<ValidationIssue> list) {
        return list.stream().map(i -> IssueDto.builder()
                .ruleId(i.getRuleId()).severity(i.getSeverity())
                .sheetName(i.getSheetName()).rowIdx(i.getRowIdx()).colIdx(i.getColIdx())
                .fieldName(i.getFieldName()).message(i.getMessage()).build())
            .collect(Collectors.toList());
    }


    private boolean isRuleEnabled(Set<String> enabledRules,
                                  String ruleId) {

        return enabledRules.contains(ruleId);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(
                    value == null ? "0" : value.trim()
            );
        } catch (Exception e) {
            return 0;
        }
    }

    private String normalizeName(String name) {

        if (name == null) {
            return "";
        }

        return name.trim()
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    private double getWorkingHoursPerDay(String employeeName) {

        return resourceRepo.findAll()
                .stream()
                .filter(r -> normalizeName(r.getName())
                        .equals(normalizeName(employeeName)))
                .map(Resource::getWorkingHoursPerDay)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(props.getDefaultWorkingHoursPerDay());
    }


    private Set<String> extractTimesheetEmployees(
            List<CellData> timesheetCells) {

        Set<String> employees = new HashSet<>();

        TreeMap<Integer, Map<Integer, CellData>> rowMap =
                new TreeMap<>();

        for (CellData c : timesheetCells) {

            rowMap.computeIfAbsent(
                    c.getRowIdx(),
                    k -> new TreeMap<>()
            ).put(c.getColIdx(), c);
        }

        int headerRow = rowMap.firstKey();

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rowMap.entrySet()) {

            if (entry.getKey() == headerRow) {
                continue;
            }

            CellData nameCell =
                    entry.getValue().get(1);

            if (nameCell != null &&
                    nameCell.getDisplayValue() != null &&
                    !nameCell.getDisplayValue().isBlank()) {

                employees.add(
                        nameCell.getDisplayValue()
                                .trim()
                                .toLowerCase()
                );
            }
        }

        return employees;
    }


    private Set<String> extractPivotEmployees(
            List<CellData> pivotCells) {

        Set<String> employees = new HashSet<>();

        TreeMap<Integer, Map<Integer, CellData>> rowMap =
                new TreeMap<>();

        for (CellData c : pivotCells) {

            rowMap.computeIfAbsent(
                    c.getRowIdx(),
                    k -> new TreeMap<>()
            ).put(c.getColIdx(), c);
        }



        boolean employeeSectionStarted = false;

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rowMap.entrySet()) {

            Map<Integer, CellData> cols =
                    entry.getValue();

            CellData firstColumn =
                    cols.get(0);

            if (firstColumn == null) {
                continue;
            }

            String value =
                    firstColumn.getDisplayValue();

            if (value == null || value.isBlank()) {
                continue;
            }

            value = value.trim();

            log.info(
                    "ROW={} VALUE={}",
                    entry.getKey(),
                    value
            );

             //Start reading employees after Row Labels
//            if ("Row Lables".equalsIgnoreCase(value)) {
//
//                employeeSectionStarted = true;
//                continue;
//            }

            log.info(
                    "FIRST COLUMN VALUE='{}'",
                    value
            );

            if (isRowLabelHeader(value)) {

                employeeSectionStarted = true;
                continue;
            }




            if (!employeeSectionStarted) {
                continue;
            }

            // Stop when Grand Total reached
            if (value.toLowerCase().contains("grand total")) {
                break;
            }

            employees.add(value.toLowerCase());
        }

        return employees;
    }


    private Map<String, Double> extractTimesheetEmployeeTotals(
            List<CellData> timesheetCells) {

        TreeMap<Integer, Map<Integer, CellData>> rowMap =
                new TreeMap<>();

        for (CellData c : timesheetCells) {

            rowMap.computeIfAbsent(
                    c.getRowIdx(),
                    k -> new TreeMap<>()
            ).put(c.getColIdx(), c);
        }



        int headerRow = rowMap.firstKey();

        Map<String, Double> totals =
                new HashMap<>();

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rowMap.entrySet()) {

            if (entry.getKey() == headerRow) {
                continue;
            }

            Map<Integer, CellData> cols =
                    entry.getValue();

            String employee =
                    val(cols, 1);

            String hoursStr =
                    val(cols, 7);

            if (employee.isBlank()
                    || hoursStr.isBlank()) {
                continue;
            }




            try {

                double hours =
                        Double.parseDouble(hoursStr);

                totals.merge(
                        employee.trim().toLowerCase(),
                        hours,
                        Double::sum
                );

            } catch (Exception ignored) {
            }
        }

        return totals;
    }


    private Integer findPivotGrandTotalColumn(List<CellData> pivotCells) {

        for (CellData cell : pivotCells) {

            if ("Grand Total".equalsIgnoreCase(cell.getDisplayValue())) {
                return cell.getColIdx();
            }

        }

        return null;
    }

    private Map<String, Double> extractPivotEmployeeTotals(
            List<CellData> pivotCells) {

        TreeMap<Integer, Map<Integer, CellData>> rowMap =
                new TreeMap<>();

        for (CellData c : pivotCells) {

            rowMap.computeIfAbsent(
                    c.getRowIdx(),
                    k -> new TreeMap<>()
            ).put(c.getColIdx(), c);
        }

//        Integer headerRow = null;
//        Integer grandTotalColumn = null;
//
//        for (Map.Entry<Integer, Map<Integer, CellData>> entry
//                : rowMap.entrySet()) {
//
//            for (CellData cell :
//                    entry.getValue().values()) {
//
//                if ("Grand Total".equalsIgnoreCase(
//                        cell.getDisplayValue())) {
//
//                    headerRow = entry.getKey();
//
//                    grandTotalColumn = cell.getColIdx();
//
//                    break;
//                }
//            }
//
//            if (grandTotalColumn != null) {
//                break;
//            }
//        }


//        Integer grandTotalColumn = findPivotGrandTotalColumn(pivotCells);

//        Integer headerRow = null;

//        if (grandTotalColumn != null) {
//
//            for (Map.Entry<Integer, Map<Integer, CellData>> entry : rowMap.entrySet()) {
//
//                if (entry.getValue().containsKey(grandTotalColumn)
//                        && "Grand Total".equalsIgnoreCase(
//                        entry.getValue().get(grandTotalColumn).getDisplayValue())) {
//
//                    headerRow = entry.getKey();
//                    break;
//                }
//            }
//        }


        PivotLayout layout = findPivotLayout(pivotCells);

        if (layout == null) {
            return new HashMap<>();
        }

        Integer headerRow = layout.getHeaderRow();
        Integer grandTotalColumn = layout.getGrandTotalColumn();


        Map<String, Double> totals =
                new HashMap<>();

        if (headerRow == null || grandTotalColumn == null) {

            return totals;
        }

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rowMap.entrySet()) {

            if (entry.getKey() <= headerRow) {
                continue;
            }

            Map<Integer, CellData> cols = entry.getValue();

            String employee = val(cols, 0);

            if (employee.isBlank()) {
                continue;
            }

            if (employee.toLowerCase()
                    .contains("grand total")) {

                break;
            }

            String totalStr = val(cols, grandTotalColumn);

            if (totalStr.isBlank()) {
                continue;
            }

            try {

                totals.put(
                        employee.trim().toLowerCase(),
                        Double.parseDouble(totalStr)
                );

            } catch (Exception ignored) {
            }
        }

        return totals;
    }



    private Map<String, Double> extractTimesheetDateTotals(
            List<CellData> allCells) {

        Map<String, Double> totals = new HashMap<>();

        Map<Integer, Map<Integer, CellData>> rows =
                allCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a, b) -> a)));

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rows.entrySet()) {

            // Skip header row
            if (entry.getKey() == 0) {
                continue;
            }

            Map<Integer, CellData> row = entry.getValue();

            CellData dateCell = row.get(0);
            CellData hoursCell = row.get(7);

            if (dateCell == null || hoursCell == null) {
                continue;
            }

            String date =
                    safe(dateCell.getRawValue());

            if (date.isEmpty()) {
                continue;
            }

            double hours =
                    parseDouble(hoursCell.getRawValue());

            totals.merge(
                    date,
                    hours,
                    Double::sum
            );
        }

        return totals;
    }


    /**
     * Locates the Pivot "Grand Total" row dynamically instead of assuming a
     * fixed index. The column header "Grand Total" sits near the top (row 3),
     * while the row label "Grand Total" sits at the bottom — so we take the
     * bottom-most match. Falls back to {@code fallback} when no label is found,
     * preserving previous behaviour for layouts that lack the label.
     */
    private int pivotGrandTotalRow(List<CellData> pivotCells, int fallback) {
        return pivotCells.stream()
                .filter(c -> c.getRowIdx() != null)
                .filter(c -> "grand total".equalsIgnoreCase(safe(c.getDisplayValue()).trim())
                          || "grand total".equalsIgnoreCase(safe(c.getRawValue()).trim()))
                .map(CellData::getRowIdx)
                .max(Integer::compareTo)
                .orElse(fallback);
    }

    private Map<String, Double> extractPivotDateTotals(
            List<CellData> pivotCells) {

        Map<String, Double> totals = new HashMap<>();

//        Map<Integer, CellData> headerRow =
//                pivotCells.stream()
//                        .filter(c -> c.getRowIdx() == 3)
//                        .collect(Collectors.toMap(
//                                CellData::getColIdx,
//                                c -> c));


        PivotLayout layout = findPivotLayout(pivotCells);

        if (layout == null) {
            return totals;
        }

        int headerRowIndex = layout.getHeaderRow();

        Map<Integer, CellData> headerRow =
                pivotCells.stream()
                        .filter(c -> c.getRowIdx() == headerRowIndex)
                        .collect(Collectors.toMap(
                                CellData::getColIdx,
                                c -> c));



        int gtRow = pivotGrandTotalRow(pivotCells, 21);
        Map<Integer, CellData> grandTotalRow =
                pivotCells.stream()
                        .filter(c -> c.getRowIdx() == gtRow)
                        .collect(Collectors.toMap(
                                CellData::getColIdx,
                                c -> c));

        for (Integer col : headerRow.keySet()) {

            CellData header = headerRow.get(col);

            if (header == null) {
                continue;
            }

            String date =
                    safe(header.getRawValue());

            if (!date.contains("-")) {
                continue;
            }

            CellData totalCell =
                    grandTotalRow.get(col);

            if (totalCell == null) {
                continue;
            }

            totals.put(
                    date,
                    parseDouble(totalCell.getRawValue())
            );
        }

        return totals;
    }


    private Map<String, Double> extractTimesheetEmployeeDateTotals(
            List<CellData> allCells) {

        Map<String, Double> totals = new HashMap<>();

        Map<Integer, Map<Integer, CellData>> rows =
                allCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a,b)->a)));

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rows.entrySet()) {

            if (entry.getKey() == 0) {
                continue;
            }

            Map<Integer, CellData> row = entry.getValue();

            CellData dateCell = row.get(0);
            CellData nameCell = row.get(1);
            CellData hoursCell = row.get(7);

            if (dateCell == null ||
                    nameCell == null ||
                    hoursCell == null) {
                continue;
            }

            String date =
                    safe(dateCell.getRawValue());

            String employee =
                    normalizeName(nameCell.getRawValue());

            double hours =
                    parseDouble(hoursCell.getRawValue());

            String key =
                    employee + "|" + date;

            totals.merge(
                    key,
                    hours,
                    Double::sum
            );
        }

        return totals;
    }


    private String pivotDateToIso(String pivotDate) {

        try {

            String datePart =
                    pivotDate.split("\\s+")[0];

            LocalDate date =
                    LocalDate.parse(
                            datePart,
                            DateTimeFormatter.ofPattern(
                                    "dd-MMM-yy",
                                    Locale.ENGLISH));

            return date.toString();
        }
        catch (Exception e) {
            return "";
        }
    }

    private Map<String, Double> extractPivotEmployeeDateTotals(
            List<CellData> pivotCells) {

        Map<String, Double> totals =
                new HashMap<>();

        Map<Integer, Map<Integer, CellData>> rows =
                pivotCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a,b)->a)));

        Map<Integer, String> dateColumns =
                new HashMap<>();

//        Map<Integer, CellData> headerRow = rows.get(3);
//
//
//        if (headerRow == null) {
//            return totals;
//        }



        PivotLayout layout = findPivotLayout(pivotCells);

        if (layout == null) {
            return totals;
        }

        int headerRowIndex =
                layout.getHeaderRow();

        Map<Integer, CellData> headerRow =
                rows.get(headerRowIndex);

        if (headerRow == null) {
            return totals;
        }



        for (Map.Entry<Integer, CellData> entry
                : headerRow.entrySet()) {

            int col =
                    entry.getKey();

            String value =
                    safe(entry.getValue().getDisplayValue());

//            if (value.contains("-")) {
//
//                dateColumns.put(
//                        col,
//                        pivotDateToIso(value));
//            }

            if (value.contains("-")) {

                String isoDate =
                        pivotDateToIso(value);

                dateColumns.put(col, isoDate);

                pivotDateColumns.put(
                        isoDate,
                        col
                );
            }
        }

        int gtRow = pivotGrandTotalRow(pivotCells, 21);
        for (Map.Entry<Integer, Map<Integer, CellData>> rowEntry
                : rows.entrySet()) {

            int rowIdx =
                    rowEntry.getKey();

//            if (rowIdx <= 3 || rowIdx >= gtRow) {
//                continue;
//            }

            if (rowIdx <= headerRowIndex || rowIdx >= gtRow) {
                continue;
            }

            Map<Integer, CellData> row =
                    rowEntry.getValue();

            CellData employeeCell =
                    row.get(0);

            if (employeeCell == null) {
                continue;
            }

            String employee =
                    normalizeName(
                            employeeCell.getDisplayValue());

            if (employee.isEmpty()) {
                continue;
            }

            for (Map.Entry<Integer, String> dateEntry
                    : dateColumns.entrySet()) {

                int col =
                        dateEntry.getKey();

                String date =
                        dateEntry.getValue();

                CellData valueCell =
                        row.get(col);

                if (valueCell == null) {
                    continue;
                }

                double hours =
                        parseDouble(
                                valueCell.getDisplayValue());

                String key =
                        employee + "|" + date;

                totals.put(
                        key,
                        hours);
            }
        }

        return totals;
    }


    private Double extractPivotGrandTotal(
            List<CellData> pivotCells) {

        Map<Integer, Map<Integer, CellData>> rows =
                pivotCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a,b)->a)));

//        Map<Integer, CellData> grandTotalRow = rows.get(21);

        int gtRow =
                pivotGrandTotalRow(
                        pivotCells,
                        21
                );

        Map<Integer, CellData> grandTotalRow =
                rows.get(gtRow);


        if (grandTotalRow == null) {
            return null;
        }

        CellData totalCell =
                grandTotalRow.get(24);

        if (totalCell == null) {
            return null;
        }

        return parseDouble(
                totalCell.getDisplayValue());
    }

    private Double calculatePivotDateColumnTotal(
            List<CellData> pivotCells) {

        Map<Integer, Map<Integer, CellData>> rows =
                pivotCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a,b)->a)));

//        Map<Integer, CellData> grandTotalRow = rows.get(21);

        int gtRow =
                pivotGrandTotalRow(
                        pivotCells,
                        21
                );

        Map<Integer, CellData> grandTotalRow =
                rows.get(gtRow);


        if (grandTotalRow == null) {
            return 0.0;
        }

        double total = 0;

        for (int col = 1; col <= 23; col++) {

            CellData cell =
                    grandTotalRow.get(col);

            if (cell == null) {
                continue;
            }

            total += parseDouble(
                    cell.getDisplayValue());
        }

        return total;
    }


    private int findWorkingDaysColumn(List<CellData> pivotCells) {

        Map<Integer, Map<Integer, CellData>> rows =
                pivotCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a, b) -> a)));

        // Header row (Row Labels)
//        Map<Integer, CellData> headerRow = rows.get(3);

        PivotLayout layout = findPivotLayout(pivotCells);

        Map<Integer, CellData> headerRow = rows.get(layout.getHeaderRow());

        if (headerRow == null) {
            return -1;
        }

        int lastColumn = headerRow.keySet()
                .stream()
                .max(Integer::compareTo)
                .orElse(-1);

        // Working Days column is immediately after the last date/Grand Total column
        return lastColumn + 1;
    }


    private Map<String, Double> extractPivotEmployeeDays(
            List<CellData> pivotCells) {

        Map<String, Double> result =
                new HashMap<>();

        Map<Integer, Map<Integer, CellData>> rows =
                pivotCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a,b)->a)));

//        int gtRow = pivotGrandTotalRow(pivotCells, 21);


//        Integer grandTotalColumn = findPivotGrandTotalColumn(pivotCells);
//
//        if (grandTotalColumn == null) {
//            return Collections.emptyMap();
//        }
//
//        int workingDaysColumn = grandTotalColumn + 1;
//
//        log.info(
//                "Grand Total Column={}, Working Days Column={}",
//                grandTotalColumn,
//                workingDaysColumn
//        );


        PivotLayout layout = findPivotLayout(pivotCells);

        if (layout == null) {
            return Collections.emptyMap();
        }

        int headerRow = layout.getHeaderRow();
        int workingDaysColumn =
                layout.getGrandTotalColumn() + 1;

        log.info(
                "Header Row={}, GrandTotal Column={}, WorkingDays Column={}",
                headerRow,
                layout.getGrandTotalColumn(),
                workingDaysColumn
        );




        for (Map.Entry<Integer, Map<Integer, CellData>> rowEntry
                : rows.entrySet()) {

            int rowIdx =
                    rowEntry.getKey();


//            if (rowIdx <= 3 || rowIdx >= 21) {
//                continue;
//            }

            if (rowIdx <= headerRow) {
                continue;
            }

            Map<Integer, CellData> row =
                    rowEntry.getValue();

            log.info("ROW {} COLUMNS {}", rowIdx, row.keySet());

            for (Map.Entry<Integer, CellData> cell : row.entrySet()) {

                log.info(
                        "ROW={} COL={} VALUE={}",
                        rowIdx,
                        cell.getKey(),
                        cell.getValue().getDisplayValue()
                );
            }


            CellData employeeCell =
                    row.get(0);

//            CellData daysCell = row.get(12);

//            int workingDaysColumn = findWorkingDaysColumn(pivotCells);

            CellData daysCell = row.get(workingDaysColumn);

            log.info(
                    "employeeCell={} daysCell={}",
                    employeeCell == null ? "NULL" : employeeCell.getDisplayValue(),
                    daysCell == null ? "NULL" : daysCell.getDisplayValue()
            );


            if (employeeCell == null || daysCell == null) {
                continue;
            }

            String employee =
                    normalizeName(
                            employeeCell.getDisplayValue());

            if ("grand total".equalsIgnoreCase(employee)) {
                break;
            }

            log.info("Normalized employee = '{}'", employee);

            double days =
                    parseDouble(
                            daysCell.getDisplayValue());

            log.info("Parsed days = {}", days);

            log.info(
                    "ADDING employee='{}' days={}",
                    employee,
                    days
            );



            result.put(employee, days);
        }
        log.info("FINAL RESULT = {}", result);
        return result;
    }



    private Map<String, Integer> extractPivotEmployeeRows(
            List<CellData> pivotCells) {

        Map<String, Integer> employeeRows =
                new HashMap<>();

        Map<Integer, Map<Integer, CellData>> rows =
                pivotCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a, b) -> a)));

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rows.entrySet()) {

            Integer rowNumber =
                    entry.getKey();

            Map<Integer, CellData> row =
                    entry.getValue();

            CellData employeeCell =
                    row.get(0);

            if (employeeCell == null) {
                continue;
            }

            String employee =
                    normalizeName(employeeCell.getRawValue());

//            if (employee.isBlank()
//                    || employee.equals("row labels")
//                    || employee.equals("grand total")) {
//                continue;
//            }

            if (employee.isBlank()
                    || isRowLabelHeader(employee)
                    || employee.equals("grand total")) {
                continue;
            }

//            int displayRow = rowNumber - 2;
//
//            employeeRows.put(employee, displayRow);

//            employeeRows.put(employee, rowNumber);

            employeeRows.put(employee, rowNumber);

//            log.info(
//                    "EMPLOYEE={} EXCEL={}",
//                    employee,
//                    rowNumber
//            );


        }

        return employeeRows;
    }


    private Map<String, Integer> extractPivotDateColumns(
            List<CellData> pivotCells) {

        Map<String, Integer> result =
                new HashMap<>();

        Map<Integer, Map<Integer, CellData>> rows =
                pivotCells.stream()
                        .collect(Collectors.groupingBy(
                                CellData::getRowIdx,
                                Collectors.toMap(
                                        CellData::getColIdx,
                                        c -> c,
                                        (a,b) -> a)));

//        Map<Integer, CellData> headerRow =
//                rows.get(3);
//
//        if (headerRow == null) {
//            return result;
//        }


        PivotLayout layout = findPivotLayout(pivotCells);

        if (layout == null) {
            return result;
        }

        Map<Integer, CellData> headerRow =
                rows.get(layout.getHeaderRow());

        if (headerRow == null) {
            return result;
        }


        for (Map.Entry<Integer, CellData> entry
                : headerRow.entrySet()) {

            int col =
                    entry.getKey();

            String value =
                    safe(entry.getValue().getDisplayValue());

            if (value.contains("-")) {

                result.put(
                        pivotDateToIso(value),
                        col
                );
            }
        }

        return result;
    }

    private boolean isRowLabelHeader(String value) {

        if (value == null) {
            return false;
        }

        String normalized =
                value.trim().toLowerCase();

        return normalized.equals("row labels")
                || normalized.equals("row lables")
                || normalized.equals("name")
                || normalized.equals("name (mandatory)");
    }


    private PivotLayout findPivotLayout(List<CellData> pivotCells) {

        TreeMap<Integer, Map<Integer, CellData>> rowMap =
                new TreeMap<>();

        for (CellData c : pivotCells) {

            rowMap.computeIfAbsent(
                    c.getRowIdx(),
                    k -> new TreeMap<>()
            ).put(c.getColIdx(), c);
        }

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rowMap.entrySet()) {

            for (CellData cell : entry.getValue().values()) {

                if ("Grand Total".equalsIgnoreCase(
                        cell.getDisplayValue())) {

                    return new PivotLayout(
                            entry.getKey(),
                            cell.getColIdx()
                    );
                }
            }
        }

        return null;
    }

    private static class PivotLayout {

        private final int headerRow;
        private final int grandTotalColumn;

        PivotLayout(int headerRow, int grandTotalColumn) {
            this.headerRow = headerRow;
            this.grandTotalColumn = grandTotalColumn;
        }

        public int getHeaderRow() {
            return headerRow;
        }

        public int getGrandTotalColumn() {
            return grandTotalColumn;
        }
    }


    private String valRaw(Map<Integer, CellData> cols, int col) {
        CellData c = cols.get(col);
        if (c == null) return "";
        String raw = c.getRawValue();
        if (raw != null && !raw.isBlank()) return raw.trim();
        return c.getDisplayValue() == null ? "" : c.getDisplayValue().trim();
    }



    private Map<ProjectKey, Double> extractTimesheetProjectTotals(List<CellData> timesheetCells) {

        TreeMap<Integer, Map<Integer, CellData>> rowMap =
                new TreeMap<>();

        for (CellData c : timesheetCells) {

                rowMap.computeIfAbsent(
                        c.getRowIdx(),
                        k -> new TreeMap<>())
                        .put(c.getColIdx(), c);
        }

        int headerRow = rowMap.firstKey();

        Map<ProjectKey, Double> totals =
                new HashMap<>();

        for (Map.Entry<Integer, Map<Integer, CellData>> entry
                : rowMap.entrySet()) {

                if (entry.getKey() == headerRow) {
                continue;
                }

                Map<Integer, CellData> cols =
                        entry.getValue();

                String project =
                        val(cols, 3);

                String hours =
                        val(cols, 7);

                if (project.isBlank() || hours.isBlank()) {
                continue;
                }

                totals.merge(
                        new ProjectKey(project),
                        parseDouble(hours),
                        Double::sum);
        }

                log.info("==========================================");
                log.info("PROJECT CODE TOTALS");
                totals.forEach((k, v) ->
                        log.info("{} -> {}", k, v));
                log.info("==========================================");
                return totals;
        }


        private Map<SubProjectKey, Double> extractTimesheetSubProjectTotals(
        List<CellData> timesheetCells) {

    TreeMap<Integer, Map<Integer, CellData>> rowMap =
            new TreeMap<>();

    for (CellData c : timesheetCells) {

        rowMap.computeIfAbsent(
                c.getRowIdx(),
                k -> new TreeMap<>())
                .put(c.getColIdx(), c);
    }

    int headerRow = rowMap.firstKey();

    Map<SubProjectKey, Double> totals =
            new HashMap<>();

    for (Map.Entry<Integer, Map<Integer, CellData>> entry
            : rowMap.entrySet()) {

        if (entry.getKey() == headerRow) {
            continue;
        }

        Map<Integer, CellData> cols =
                entry.getValue();

        String project =
                val(cols, 3);

        String subProject =
                val(cols, 4);

        String hours =
                val(cols, 7);

        if (project.isBlank()
                || subProject.isBlank()
                || hours.isBlank()) {

            continue;
        }

        totals.merge(
                new SubProjectKey(project, subProject),
                parseDouble(hours),
                Double::sum);
    }

    return totals;
}


private Map<ProjectCodeKey, Double> extractTimesheetProjectCodeTotals(
        List<CellData> timesheetCells) {

    TreeMap<Integer, Map<Integer, CellData>> rowMap =
            new TreeMap<>();

    for (CellData c : timesheetCells) {

        rowMap.computeIfAbsent(
                c.getRowIdx(),
                k -> new TreeMap<>())
                .put(c.getColIdx(), c);
    }

    int headerRow = rowMap.firstKey();

    Map<ProjectCodeKey, Double> totals =
            new HashMap<>();

    for (Map.Entry<Integer, Map<Integer, CellData>> entry
            : rowMap.entrySet()) {

        if (entry.getKey() == headerRow) {
            continue;
        }

        Map<Integer, CellData> cols =
                entry.getValue();

        String project =
                val(cols, 3);

        String subProject =
                val(cols, 4);

        String projectCode =
                val(cols, 5);

        String hours =
                val(cols, 7);

        if (project.isBlank()
                || subProject.isBlank()
                || projectCode.isBlank()
                || hours.isBlank()) {

            continue;
        }

        double rowHours = parseDouble(hours);

        ProjectCodeKey key = new ProjectCodeKey(
                project,
                subProject,
                projectCode);

        log.info(
                "PW-003 ROW -> Project='{}', SubProject='{}', ProjectCode='{}', Hours={}",
                project,
                subProject,
                projectCode,
                rowHours);

        totals.merge(
                key,
                rowHours,
                Double::sum);
    }

    return totals;
}


private ValidationIssue projectWiseIssue(
        String sid,
        String ruleId,
        String severity,
        int row,
        int col,
        String field,
        String msg) {

    return ValidationIssue.builder()
            .sessionId(sid)
            .ruleId(ruleId)
            .severity(severity)
            .sheetName(PROJECT_WISE_SHEET)
            .rowIdx(row)
            .colIdx(col)
            .fieldName(field)
            .message(renderMessage(
                    ruleId,
                    severity,
                    field,
                    msg))
            .build();
}

        private ValidationIssue projectWiseIssue(
                String sid,
                String ruleId,
                String severity,
                CellReference cell,
                String message) {

        return projectWiseIssue(
                sid,
                ruleId,
                severity,
                cell.getRow(),
                cell.getColumn(),
                cell.getFieldName(),
                message);
        }


/**
 * Performs Project Wise validation.
 *
 * PW-001 : Project totals
 * PW-002 : Sub Project totals
 * PW-003 : Project Code totals
 */
private void validateProjectWise(
        String sessionId,
        List<CellData> timesheetCells,
        List<CellData> projectWiseCells,
        List<ValidationIssue> issues) {

    log.info("==================================================");
    log.info("Starting Project Wise Validation");
    log.info("==================================================");

    if (projectWiseCells == null || projectWiseCells.isEmpty()) {

        log.warn("Project Wise sheet not found. Skipping validation.");
        return;
    }

    ProjectWiseHierarchy hierarchy =
            projectWiseParser.parse(projectWiseCells);

    if (hierarchy.isEmpty()) {

        log.warn("Project Wise sheet contains no parsable data.");
        return;
    }

    Map<ProjectKey, Double> projectTotals =
            extractTimesheetProjectTotals(timesheetCells);

    Map<SubProjectKey, Double> subProjectTotals =
            extractTimesheetSubProjectTotals(timesheetCells);

    Map<ProjectCodeKey, Double> projectCodeTotals =
            extractTimesheetProjectCodeTotals(timesheetCells);

    log.info("Projects parsed      : {}", hierarchy.getProjects().size());
    log.info("Sub Projects parsed  : {}", hierarchy.getSubProjects().size());
    log.info("Project Codes parsed : {}", hierarchy.getProjectCodes().size());

    // ── Hours-based validation (existing rules) ────────────────
    validateProjects(
            sessionId,
            hierarchy,
            projectTotals,
            issues);

    validateSubProjects(
            sessionId,
            hierarchy,
            subProjectTotals,
            issues);

    validateProjectCodes(
            sessionId,
            hierarchy,
            projectCodeTotals,
            issues);

    // ── Structural validation (new rules for bugs 1.1-1.8) ─────
    Set<String> timesheetProjectNames = projectTotals.keySet().stream()
            .map(ProjectKey::getProjectName)
            .collect(Collectors.toSet());

    Set<SubProjectKey> timesheetSubProjectKeys = subProjectTotals.keySet();

    Set<String> timesheetSubProjectNames = timesheetSubProjectKeys.stream()
            .map(SubProjectKey::getSubProjectName)
            .collect(Collectors.toSet());

    Set<ProjectCodeKey> timesheetProjectCodeKeys = projectCodeTotals.keySet();

    Set<String> timesheetProjectCodeValues = timesheetProjectCodeKeys.stream()
            .map(ProjectCodeKey::getProjectCode)
            .collect(Collectors.toSet());

    // PW-004: Missing Project in Project-wise sheet          (bug 1.1)
    validateMissingProjects(sessionId, hierarchy, timesheetProjectNames, timesheetSubProjectKeys, issues);

    // PW-005: Extra/Invalid Project in Project-wise sheet     (bug 1.2)
    validateExtraProjects(sessionId, hierarchy, timesheetProjectNames, issues);

    // PW-006: Missing Sub-Project in Project-wise sheet       (bug 1.3)
    validateMissingSubProjects(sessionId, hierarchy, timesheetSubProjectKeys, timesheetProjectCodeKeys, issues);

    // PW-007: Wrong Project-SubProject mapping                (bug 1.4)
    // PW-008: Extra/Invalid Sub-Project in Project-wise sheet (bug 1.5)
    validateSubProjectStructure(sessionId, hierarchy, timesheetSubProjectKeys, timesheetSubProjectNames, issues);

    // PW-009: Missing Project Code in Project-wise sheet      (bug 1.6)
    validateMissingProjectCodes(sessionId, hierarchy, timesheetProjectCodeKeys, timesheetSubProjectKeys, issues);

    // PW-010: Wrong SubProject-ProjectCode mapping            (bug 1.7)
    // PW-011: Extra/Invalid Project Code in Project-wise sheet(bug 1.8)
    validateProjectCodeStructure(sessionId, hierarchy, timesheetProjectCodeKeys, timesheetProjectCodeValues, issues);

    log.info("Project Wise Validation completed.");
}


// =================================================================
// NEW STRUCTURAL VALIDATION METHODS (Bugs 1.1 - 1.8)
// =================================================================

/**
 * PW-004
 *
 * Detects projects present in the Timesheet but missing from the
 * Project-wise sheet. (Bug 1.1)
 */
private void validateMissingProjects(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Set<String> timesheetProjectNames,
        Set<SubProjectKey> timesheetSubProjectKeys,
        List<ValidationIssue> issues) {

    log.info("Starting PW-004 (Missing Project) validation...");

    log.info("PW-004: Timesheet projects ({} total): {}", timesheetProjectNames.size(), timesheetProjectNames);
    log.info("PW-004: Hierarchy project cells ({} total): {}",
            hierarchy.getHierarchyProjectCells().size(), hierarchy.getHierarchyProjectCells().keySet());

    for (String projectName : timesheetProjectNames) {

        // Check if project is missing from the Hierarchy table (Table 2, column D)
        if (!hierarchy.getHierarchyProjectCells().containsKey(projectName)) {

            log.warn("PW-004 failed. Project='{}' missing from Hierarchy table (column D).", projectName);

            // Try to find a cell reference: look for a sub-project that belongs to this
            // project in the Timesheet; if found in the hierarchy, use its row.
            int cellRow = findMissingProjectRow(projectName, timesheetSubProjectKeys, hierarchy);

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-004",
                    "CRITICAL",
                    cellRow >= 0 ? cellRow : 2,
                    3,
                    "Project-wise",
                    String.format(
                            "Project '%s' is present in the Timesheet but missing from the Project-wise sheet.",
                            projectName)));

            log.info("PW-004: ISSUE ADDED for missing Project='{}' at row={}", projectName, cellRow >= 0 ? cellRow : 2);
        } else {
            log.info("PW-004: Project='{}' found in Hierarchy table.", projectName);
        }
    }

    log.info("Completed PW-004 validation.");
}


/**
 * PW-005
 *
 * Detects extra/invalid projects in the Project-wise sheet that
 * do not exist in the Timesheet data. (Bug 1.2)
 */
private void validateExtraProjects(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Set<String> timesheetProjectNames,
        List<ValidationIssue> issues) {

    log.info("Starting PW-005 (Extra Project) validation...");

    // ── Check projects from Project Summary table (Table 1, cols A-B) ──────
    for (ProjectSummary project : hierarchy.getProjects()) {

        if (!timesheetProjectNames.contains(project.getProjectName())) {

            log.warn("PW-005 failed. Extra Project='{}' not found in Timesheet (Project Summary table).",
                    project.getProjectName());

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-005",
                    "CRITICAL",
                    project.getHoursCell().getRow(),
                    0,
                    "Project",
                    String.format(
                            "Invalid Project '%s' detected in Project-wise sheet. Project does not exist in Timesheet data.",
                            project.getProjectName())));
        }
    }

    // ── Check projects from Hierarchy table (Table 2, col D, index 3) ──────
    Map<String, CellReference> hierarchyProjectCells = hierarchy.getHierarchyProjectCells();

    for (Map.Entry<String, CellReference> entry : hierarchyProjectCells.entrySet()) {

        String projectName = entry.getKey();

        // Skip if already checked via the Project Summary table
        if (hierarchy.getProjectNames().contains(projectName)) {
            continue;
        }

        if (!timesheetProjectNames.contains(projectName)) {

            CellReference cellRef = entry.getValue();

            log.warn("PW-005 failed. Extra Project='{}' not found in Timesheet (Hierarchy table, row={}).",
                    projectName, cellRef.getRow());

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-005",
                    "CRITICAL",
                    cellRef.getRow(),
                    cellRef.getColumn(),   // col 3 (D) for Hierarchy table
                    "Project",
                    String.format(
                            "Invalid Project '%s' detected in Project-wise sheet. Project does not exist in Timesheet data.",
                            projectName)));
        }
    }

    log.info("Completed PW-005 validation.");
}


/**
 * PW-006
 *
 * Detects sub-projects present in the Timesheet but missing from the
 * Project-wise sheet. (Bug 1.3)
 */
private void validateMissingSubProjects(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Set<SubProjectKey> timesheetSubProjectKeys,
        Set<ProjectCodeKey> timesheetProjectCodeKeys,
        List<ValidationIssue> issues) {

    log.info("Starting PW-006 (Missing Sub-Project) validation...");

    for (SubProjectKey tsKey : timesheetSubProjectKeys) {

        if (!hierarchy.containsSubProject(tsKey.getProjectName(), tsKey.getSubProjectName())) {

            log.warn("PW-006 failed. Sub-Project='{}' under Project='{}' missing from Project-wise sheet.",
                    tsKey.getSubProjectName(), tsKey.getProjectName());

            // Try to find a cell reference: look for a project code that belongs to this
            // sub-project in the Timesheet; if found in the hierarchy, use its row.
            int cellRow = findMissingSubProjectRow(tsKey, timesheetProjectCodeKeys, hierarchy);

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-006",
                    "CRITICAL",
                    cellRow >= 0 ? cellRow : 2,
                    4,
                    "Project-wise",
                    String.format(
                            "Sub-Project '%s' under Project '%s' is present in the Timesheet but missing from the Project-wise sheet.",
                            tsKey.getSubProjectName(),
                            tsKey.getProjectName())));
        }
    }

    log.info("Completed PW-006 validation.");
}


/**
 * PW-007 / PW-008
 *
 * For each sub-project in the Project-wise hierarchy:
 * - If the (project, subProject) pair doesn't exist in the Timesheet:
 *   - If the sub-project name exists in the Timesheet (under a different project)
 *     -> PW-007: Wrong Project-SubProject mapping (bug 1.4)
 *   - If the sub-project name does NOT exist in the Timesheet at all
 *     -> PW-008: Invalid/Extra Sub-Project (bug 1.5)
 */
private void validateSubProjectStructure(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Set<SubProjectKey> timesheetSubProjectKeys,
        Set<String> timesheetSubProjectNames,
        List<ValidationIssue> issues) {

    log.info("Starting PW-007/PW-008 (Sub-Project structure) validation...");

    for (SubProjectSummary sp : hierarchy.getSubProjects()) {

        SubProjectKey key = new SubProjectKey(sp.getProjectName(), sp.getSubProjectName());

        if (timesheetSubProjectKeys.contains(key)) {
            // This exact (project, subProject) pair exists in Timesheet.
            // Hours are validated by PW-002, so skip here.
            continue;
        }

        // The pair is not in Timesheet. Check if the sub-project name exists at all.
        if (timesheetSubProjectNames.contains(sp.getSubProjectName())) {

            // Sub-project name exists in Timesheet but under a different project -> wrong mapping
            log.warn("PW-007 failed. Sub-Project='{}' mapped under wrong Project='{}'.",
                    sp.getSubProjectName(), sp.getProjectName());

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-007",
                    "CRITICAL",
                    sp.getHoursCell().getRow(),
                    4,
                    "Sub Project",
                    String.format(
                            "Invalid Sub-project mapping. Sub-Project '%s' for Project '%s'.",
                            sp.getSubProjectName(),
                            sp.getProjectName())));

        } else {

            // Sub-project name does not exist in Timesheet at all -> extra/invalid
            log.warn("PW-008 failed. Extra Sub-Project='{}' under Project='{}' not found in Timesheet.",
                    sp.getSubProjectName(), sp.getProjectName());

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-008",
                    "CRITICAL",
                    sp.getHoursCell().getRow(),
                    4,
                    "Sub Project",
                    String.format(
                            "Invalid Sub-Project detected in Project-wise sheet. Sub-Project '%s' does not exist in Timesheet data.",
                            sp.getSubProjectName())));
        }
    }

    log.info("Completed PW-007/PW-008 validation.");
}


/**
 * PW-009
 *
 * Detects project codes present in the Timesheet but missing from the
 * Project-wise sheet. (Bug 1.6)
 */
private void validateMissingProjectCodes(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Set<ProjectCodeKey> timesheetProjectCodeKeys,
        Set<SubProjectKey> timesheetSubProjectKeys,
        List<ValidationIssue> issues) {

    log.info("Starting PW-009 (Missing Project Code) validation...");

    log.info("PW-009: Timesheet project code keys ({} total):", timesheetProjectCodeKeys.size());
    for (ProjectCodeKey k : timesheetProjectCodeKeys) {
        log.info("PW-009:   Timesheet key: Project='{}', SubProject='{}', ProjectCode='{}'",
                k.getProjectName(), k.getSubProjectName(), k.getProjectCode());
    }

    log.info("PW-009: Hierarchy project code values ({} total):", hierarchy.getProjectCodeValues().size());
    for (String v : hierarchy.getProjectCodeValues()) {
        log.info("PW-009:   Hierarchy value: '{}'", v);
    }

    log.info("PW-009: Hierarchy project code keys ({} total):", hierarchy.getProjectCodeKeys().size());
    for (ProjectCodeKey k : hierarchy.getProjectCodeKeys()) {
        log.info("PW-009:   Hierarchy key: Project='{}', SubProject='{}', ProjectCode='{}'",
                k.getProjectName(), k.getSubProjectName(), k.getProjectCode());
    }

    for (ProjectCodeKey tsKey : timesheetProjectCodeKeys) {

        log.info("PW-009: Checking tsKey Project='{}', SubProject='{}', ProjectCode='{}'",
                tsKey.getProjectName(), tsKey.getSubProjectName(), tsKey.getProjectCode());

        boolean containsInHierarchy = hierarchy.containsProjectCode(
                tsKey.getProjectName(), tsKey.getSubProjectName(), tsKey.getProjectCode());
        log.info("PW-009:   containsProjectCode={}", containsInHierarchy);

        // If the project code value exists anywhere in the hierarchy (even under a
        // different sub-project or null sub-project), it is not "missing" — it is a
        // mapping issue handled by PW-010. Skip PW-009 in that case.
        if (!containsInHierarchy) {

            boolean valueExists = hierarchy.getProjectCodeValues().contains(tsKey.getProjectCode());
            log.info("PW-009:   getProjectCodeValues().contains('{}')={}", tsKey.getProjectCode(), valueExists);

            // Check if the project code value exists somewhere in the hierarchy
            if (valueExists) {
                // It's a mapping issue, not missing — skip PW-009
                log.info("PW-009 skipped for ProjectCode='{}' — value exists in hierarchy under a different path.",
                        tsKey.getProjectCode());
                continue;
            }

            log.warn("PW-009 failed. ProjectCode='{}' under SubProject='{}' of Project='{}' missing from Project-wise sheet.",
                    tsKey.getProjectCode(), tsKey.getSubProjectName(), tsKey.getProjectName());

            // Try to find a cell reference: look for a sub-project that belongs to this
            // project code's parent in the Timesheet; if found in the hierarchy, use its row.
            int cellRow = findMissingProjectCodeRow(tsKey, timesheetSubProjectKeys, hierarchy);
            log.info("PW-009:   cellRow from findMissingProjectCodeRow = {}", cellRow);

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-009",
                    "CRITICAL",
                    cellRow >= 0 ? cellRow : 2,
                    5,
                    "Project-wise",
                    String.format(
                            "Project Code '%s' under Sub-Project '%s' of Project '%s' is present in the Timesheet but missing from the Project-wise sheet.",
                            tsKey.getProjectCode(),
                            tsKey.getSubProjectName(),
                            tsKey.getProjectName())));
            log.info("PW-009:   ISSUE ADDED for ProjectCode='{}'", tsKey.getProjectCode());
        } else {
            log.info("PW-009:   Skipping (already present in hierarchy)");
        }
    }

    log.info("Completed PW-009 validation.");
}


/**
 * PW-010 / PW-011
 *
 * For each project code in the Project-wise hierarchy:
 * - If the (project, subProject, projectCode) triple doesn't exist in the Timesheet:
 *   - If the project code value exists in the Timesheet (under a different sub-project)
 *     -> PW-010: Wrong SubProject-ProjectCode mapping (bug 1.7)
 *   - If the project code does NOT exist in the Timesheet at all
 *     -> PW-011: Invalid/Extra Project Code (bug 1.8)
 */
private void validateProjectCodeStructure(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Set<ProjectCodeKey> timesheetProjectCodeKeys,
        Set<String> timesheetProjectCodeValues,
        List<ValidationIssue> issues) {

    log.info("Starting PW-010/PW-011 (Project Code structure) validation...");

    for (ProjectCodeSummary pc : hierarchy.getProjectCodes()) {

        ProjectCodeKey key = new ProjectCodeKey(
                pc.getProjectName(), pc.getSubProjectName(), pc.getProjectCode());

        if (timesheetProjectCodeKeys.contains(key)) {
            // This exact triple exists in Timesheet. Hours are validated by PW-003, skip.
            continue;
        }

        // The triple is not in Timesheet. Check if the project code value exists at all.
        if (timesheetProjectCodeValues.contains(pc.getProjectCode())) {

            // Project code exists in Timesheet but under a different sub-project -> wrong mapping
            log.warn("PW-010 failed. ProjectCode='{}' mapped under wrong Sub-Project='{}' of Project='{}'.",
                    pc.getProjectCode(), pc.getSubProjectName(), pc.getProjectName());

            String subProjectLabel = pc.getSubProjectName() != null
                    ? pc.getSubProjectName()
                    : "(no sub-project)";

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-010",
                    "CRITICAL",
                    pc.getHoursCell().getRow(),
                    5,
                    "Project Code",
                    String.format(
                            "Invalid PCode mapping. PCode '%s' does not belong to Sub-Project '%s'.",
                            pc.getProjectCode(),
                            subProjectLabel)));

        } else {

            // Project code does not exist in Timesheet at all -> extra/invalid
            log.warn("PW-011 failed. Extra ProjectCode='{}' under Sub-Project='{}' not found in Timesheet.",
                    pc.getProjectCode(), pc.getSubProjectName());

            issues.add(projectWiseIssue(
                    sessionId,
                    "PW-011",
                    "CRITICAL",
                    pc.getHoursCell().getRow(),
                    5,
                    "Project Code",
                    String.format(
                            "Invalid Projectcode detected in Project-wise sheet. Projectcode '%s' does not exist in Timesheet data.",
                            pc.getProjectCode())));
        }
    }

    log.info("Completed PW-010/PW-011 validation.");
}


// =================================================================
// Helper methods for finding cell references of missing entries
// =================================================================

/**
 * For a missing project, tries to find a row in the hierarchy where the
 * project name should have appeared. Looks for a sub-project from the
 * Timesheet that belongs to this project, then checks if the hierarchy has
 * a sub-project with that name. Returns the row index, or -1 if not found.
 */
private int findMissingProjectRow(
        String projectName,
        Set<SubProjectKey> timesheetSubProjectKeys,
        ProjectWiseHierarchy hierarchy) {

    for (SubProjectKey spKey : timesheetSubProjectKeys) {
        if (spKey.getProjectName().equals(projectName)) {
            // This sub-project belongs to the missing project in the Timesheet.
            // Check if the hierarchy has a sub-project with this name.
            for (SubProjectSummary sp : hierarchy.getSubProjects()) {
                if (sp.getSubProjectName().equals(spKey.getSubProjectName())) {
                    return sp.getHoursCell().getRow();
                }
            }
        }
    }
    return -1;
}

/**
 * For a missing sub-project, tries to find a row in the hierarchy where the
 * sub-project name should have appeared. Looks for a project code from the
 * Timesheet that belongs to this sub-project, then checks if the hierarchy
 * has a project code with that value. Returns the row index, or -1 if not found.
 */
private int findMissingSubProjectRow(
        SubProjectKey tsKey,
        Set<ProjectCodeKey> timesheetProjectCodeKeys,
        ProjectWiseHierarchy hierarchy) {

    for (ProjectCodeKey pcKey : timesheetProjectCodeKeys) {
        if (pcKey.getProjectName().equals(tsKey.getProjectName())
                && pcKey.getSubProjectName().equals(tsKey.getSubProjectName())) {
            // This project code belongs to the missing sub-project in the Timesheet.
            // Check if the hierarchy has a project code with this value.
            for (ProjectCodeSummary pc : hierarchy.getProjectCodes()) {
                if (pc.getProjectCode().equals(pcKey.getProjectCode())) {
                    return pc.getHoursCell().getRow();
                }
            }
        }
    }
    return -1;
}

/**
 * For a missing project code, tries to find a row in the hierarchy where the
 * project code should have appeared. Looks for the sub-project that this
 * project code belongs to in the Timesheet, then checks if the hierarchy
 * has a sub-project with that name. Returns the row index, or -1 if not found.
 */
private int findMissingProjectCodeRow(
        ProjectCodeKey tsKey,
        Set<SubProjectKey> timesheetSubProjectKeys,
        ProjectWiseHierarchy hierarchy) {

    // Find the sub-project name that this project code belongs to in the Timesheet.
    String targetSubProject = tsKey.getSubProjectName();

    log.info("findMissingProjectCodeRow: looking for subProject='{}' in hierarchy sub-projects ({} total)",
            targetSubProject, hierarchy.getSubProjects().size());

    // Look for a sub-project in the hierarchy with this name.
    for (SubProjectSummary sp : hierarchy.getSubProjects()) {
        log.info("findMissingProjectCodeRow:   checking SubProject='{}' under Project='{}'",
                sp.getSubProjectName(), sp.getProjectName());
        if (sp.getSubProjectName().equals(targetSubProject)) {
            log.info("findMissingProjectCodeRow:   FOUND at row {}", sp.getHoursCell().getRow());
            return sp.getHoursCell().getRow();
        }
    }

    log.info("findMissingProjectCodeRow: fallback - checking project codes for value='{}'", tsKey.getProjectCode());

    // Fallback: also check project codes in the hierarchy for the same value.
    for (ProjectCodeSummary pc : hierarchy.getProjectCodes()) {
        log.info("findMissingProjectCodeRow:   checking ProjectCode='{}' under SubProject='{}'",
                pc.getProjectCode(), pc.getSubProjectName());
        if (pc.getProjectCode().equals(tsKey.getProjectCode())) {
            return pc.getHoursCell().getRow();
        }
    }

    return -1;
}


// =================================================================
// EXISTING HOURS-BASED VALIDATION METHODS (unchanged below)
// =================================================================


/**
 * PW-001
 *
 * Validates Project totals between the Project Wise sheet
 * and the Timesheet sheet.
 */
private void validateProjects(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Map<ProjectKey, Double> projectTotals,
        List<ValidationIssue> issues) {

    log.info("Starting Project validation...");

    for (ProjectSummary project : hierarchy.getProjects()) {

        ProjectKey key = new ProjectKey(
                project.getProjectName());

        double actualHours = project.getHours();

        double expectedHours = projectTotals.getOrDefault(
                key,
                0d);

        if (Double.compare(expectedHours, actualHours) != 0) {

            log.warn(
                    "PW-001 failed. Project='{}', Expected={}, Actual={}",
                    project.getProjectName(),
                    expectedHours,
                    actualHours);

            issues.add(
                projectWiseIssue(
                        sessionId,
                        "PW-001",
                        "CRITICAL",
                        project.getHoursCell(),
                        String.format(
                                "Project '%s' hours mismatch. Expected %.2f hours but found %.2f hours.",
                                project.getProjectName(),
                                expectedHours,
                                actualHours)));
        }
    }

    log.info("Completed Project validation.");
}


/**
 * PW-002
 *
 * Validates Sub Project totals between the Project Wise sheet
 * and the Timesheet sheet.
 */
private void validateSubProjects(
        String sessionId,
        ProjectWiseHierarchy hierarchy,
        Map<SubProjectKey, Double> subProjectTotals,
        List<ValidationIssue> issues) {

    log.info("Starting Sub Project validation...");

    for (SubProjectSummary subProject : hierarchy.getSubProjects()) {

        SubProjectKey key =
                new SubProjectKey(
                        subProject.getProjectName(),
                        subProject.getSubProjectName());

        double actualHours =
                subProject.getHours();

        double expectedHours =
                subProjectTotals.getOrDefault(
                        key,
                        0d);

        if (Double.compare(expectedHours, actualHours) != 0) {

            log.warn(
                    "PW-002 failed. Project='{}', SubProject='{}', Expected={}, Actual={}",
                    subProject.getProjectName(),
                    subProject.getSubProjectName(),
                    expectedHours,
                    actualHours);

            issues.add(
                projectWiseIssue(
                        sessionId,
                        "PW-002",
                        "CRITICAL",
                        subProject.getHoursCell(),
                        String.format(
                                "Sub Project '%s' under Project '%s' has incorrect hours. Expected %.2f hours but found %.2f hours.",
                                subProject.getSubProjectName(),
                                subProject.getProjectName(),
                                expectedHours,
                                actualHours
                        )));
        }
    }

    log.info("Completed Sub Project validation.");
}


        /**
         * PW-003
         *
         * Validates Project Code totals between the Project Wise sheet
         * and the Timesheet sheet.
         */
        private void validateProjectCodes(
                String sessionId,
                ProjectWiseHierarchy hierarchy,
                Map<ProjectCodeKey, Double> projectCodeTotals,
                List<ValidationIssue> issues) {

        log.info("Starting Project Code validation...");

        for (ProjectCodeSummary projectCode
                : hierarchy.getProjectCodes()) {

                ProjectCodeKey key =
                        new ProjectCodeKey(
                                projectCode.getProjectName(),
                                projectCode.getSubProjectName(),
                                projectCode.getProjectCode());

                double actualHours =
                        projectCode.getHours();

                double expectedHours =
                        projectCodeTotals.getOrDefault(
                                key,
                                0d);


                log.info(
                "COMPARE -> key={} expected={} actual={}",
                key,
                expectedHours,
                actualHours);

                if (Double.compare(expectedHours, actualHours) != 0) {

                log.warn(
                        "PW-003 failed. Project='{}', SubProject='{}', ProjectCode='{}', Expected={}, Actual={}",
                        projectCode.getProjectName(),
                        projectCode.getSubProjectName(),
                        projectCode.getProjectCode(),
                        expectedHours,
                        actualHours);

                issues.add(
                        projectWiseIssue(
                                sessionId,
                                "PW-003",
                                "CRITICAL",
                                projectCode.getHoursCell(),
                                String.format(
                                        "Project Code '%s' under Sub Project '%s' of Project '%s' has incorrect hours. Expected %.2f hours but found %.2f hours.",
                                        projectCode.getProjectCode(),
                                        projectCode.getSubProjectName(),
                                        projectCode.getProjectName(),
                                        expectedHours,
                                        actualHours
                                )));
                }
        }

        log.info("Completed Project Code validation.");
        }


    // ======================================================
    // SUMMARY VALIDATION
    // ======================================================
    // ======================================================
    // SUMMARY VALIDATION
    // ======================================================

    /**
     * Build a ValidationIssue for the Summary sheet.
     */
    private ValidationIssue summaryIssue(
            String sid,
            String ruleId,
            String severity,
            int row,
            int col,
            String field,
            String msg) {

        return ValidationIssue.builder()
                .sessionId(sid)
                .ruleId(ruleId)
                .severity(severity)
                .sheetName(SUMMARY_SHEET)
                .rowIdx(row)
                .colIdx(col)
                .fieldName(field)
                .message(renderMessage(ruleId, severity, field, msg))
                .build();
    }


    /**
     * Validates the Summary sheet against the DB and the Timesheet/Pivot data.
     *
     * Summary sheet layout (0-based):
     *   Row 0: Title (ignore)
     *   Row 1: Header (col 0=Sow No, 1=SOW Description, 2=PO#, 3=Name,
     *           4=Location, 5=Daily Rate, 6=Start Date, 7=End Date,
     *           8=Days Worked, 9=Travel Expense, 10=Total Amount, 11=Remarks)
     *   Rows 2..N-1: Data rows
     *   Row N: Totals row
     */
    private void validateSummary(
            String sessionId,
            List<CellData> timesheetCells,
            List<CellData> pivotCells,
            List<CellData> summaryCells,
            List<ValidationIssue> issues) {

        log.info("==================================================");
        log.info("Starting Summary Validation");
        log.info("==================================================");

        // Build row map for Summary sheet
        TreeMap<Integer, Map<Integer, CellData>> summaryRowMap = new TreeMap<>();
        for (CellData c : summaryCells) {
            summaryRowMap.computeIfAbsent(c.getRowIdx(), k -> new TreeMap<>()).put(c.getColIdx(), c);
        }

        if (summaryRowMap.isEmpty()) {
            log.warn("Summary sheet is empty.");
            return;
        }

        int firstKey = summaryRowMap.firstKey();
        int lastKey = summaryRowMap.lastKey();

        // Row 0 is title, Row 1 is header
        int dataStartRow = firstKey + 2;

        // Preload pivot days map for SM-07
        Map<String, Double> pivotDays = new HashMap<>();
        if (pivotCells != null && !pivotCells.isEmpty()) {
            pivotDays = extractPivotEmployeeDays(pivotCells);
        }

        // Preload timesheet employee totals for SM-07 calculation
        Map<String, Double> timesheetTotals = new HashMap<>();
        if (timesheetCells != null && !timesheetCells.isEmpty()) {
            timesheetTotals = extractTimesheetEmployeeTotals(timesheetCells);
        }

        // Preload resource data for lookups
        Map<String, com.timesheet.validator.domain.Resource> resourceByName = new HashMap<>();
        resourceRepo.findAll().forEach(r -> resourceByName.put(r.getName().trim().toLowerCase(), r));

        // Preload SOW master data
        Map<String, com.timesheet.validator.domain.SowMaster> sowByNumber = new HashMap<>();
        sowMasterRepo.findAll().forEach(s -> sowByNumber.put(s.getSowNumber(), s));

        // Iterate data rows (skip title row 0 and header row 1, skip totals row)
        for (Map.Entry<Integer, Map<Integer, CellData>> entry : summaryRowMap.entrySet()) {

            int rowIdx = entry.getKey();
            if (rowIdx < dataStartRow) continue;
            if (rowIdx == lastKey) continue; // skip totals row

            Map<Integer, CellData> cols = entry.getValue();

            String sowNo = val(cols, 0);
            String sowDesc = val(cols, 1);
            String poNumber = val(cols, 2);
            String employeeName = val(cols, 3);
            String dailyRateStr = val(cols, 5);
            String startDateStr = val(cols, 6);
            String endDateStr = val(cols, 7);
            String daysWorkedStr = val(cols, 8);
            String travelExpenseStr = val(cols, 9);
            String totalAmountStr = val(cols, 10);

            if (sowNo.isBlank() && employeeName.isBlank()) continue;

            // =========================================
            // SM-01: SOW No + Description
            // =========================================
            if (!sowNo.isBlank()) {
                com.timesheet.validator.domain.SowMaster sow = sowByNumber.get(sowNo.trim());
                if (sow == null) {
                    issues.add(summaryIssue(
                            sessionId, "SM-01", "CRITICAL", rowIdx, 0, "Sow No",
                            String.format("SOW '%s' not found in SOW_MASTER table.", sowNo)));
                } else if (!sowDesc.isBlank() && !sowDesc.trim().equalsIgnoreCase(sow.getDescription())) {
                    issues.add(summaryIssue(
                            sessionId, "SM-01", "CRITICAL", rowIdx, 1, "SOW Description",
                            String.format("SOW Description mismatch for '%s'. Expected '%s', found '%s'.",
                                    sowNo, sow.getDescription(), sowDesc)));
                }
            }

            // =========================================
            // SM-05: PO Number
            // =========================================
            if (!sowNo.isBlank() && !poNumber.isBlank()) {
                com.timesheet.validator.domain.SowMaster sow = sowByNumber.get(sowNo.trim());
                if (sow != null) {
                    String expectedPo = sow.getPoNumber();
                    if (expectedPo != null && !expectedPo.isBlank()) {
                        // Normalize: PO numbers may appear as scientific notation in Excel
                        String normalizedPo = poNumber.trim();
                        if (normalizedPo.contains("E") || normalizedPo.contains("e")) {
                            try {
                                normalizedPo = String.valueOf((long) Double.parseDouble(normalizedPo));
                            } catch (NumberFormatException ignored) {}
                        }
                        if (!expectedPo.equals(normalizedPo)) {
                            issues.add(summaryIssue(
                                    sessionId, "SM-05", "CRITICAL", rowIdx, 2, "PO#",
                                    String.format("PO Number mismatch for SOW '%s'. Expected '%s', found '%s'.",
                                            sowNo, expectedPo, poNumber)));
                        }
                    }
                }
            }

            // =========================================
            // SM-02: Employee Name
            // =========================================
            if (!employeeName.isBlank()) {
                String normalizedName = employeeName.trim().toLowerCase();
                com.timesheet.validator.domain.Resource resource = resourceByName.get(normalizedName);
                if (resource == null) {
                    issues.add(summaryIssue(
                            sessionId, "SM-02", "CRITICAL", rowIdx, 3, "Name",
                            String.format("Employee '%s' not found in RESOURCE table.", employeeName)));
                } else if (!sowNo.isBlank()) {
                    boolean mapped = resourceSowRepo.existsByResourceIdAndSowNumber(
                            resource.getResourceId(), sowNo.trim());
                    if (!mapped) {
                        issues.add(summaryIssue(
                                sessionId, "SM-02", "CRITICAL", rowIdx, 3, "Name",
                                String.format("Employee '%s' (ID: %s) is not mapped to SOW '%s'.",
                                        employeeName, resource.getResourceId(), sowNo)));
                    }
                }
            }

            // =========================================
            // SM-03: Daily Rate
            // =========================================
            if (!employeeName.isBlank() && !dailyRateStr.isBlank()) {
                String normalizedName = employeeName.trim().toLowerCase();
                com.timesheet.validator.domain.Resource resource = resourceByName.get(normalizedName);
                if (resource != null && resource.getDailyRateUsd() != null) {
                    try {
                        double actualRate = Double.parseDouble(dailyRateStr.trim());
                        double expectedRate = resource.getDailyRateUsd().doubleValue();
                        if (Math.abs(actualRate - expectedRate) > 0.01) {
                            issues.add(summaryIssue(
                                    sessionId, "SM-03", "CRITICAL", rowIdx, 5, "Daily Rate",
                                    String.format("Daily Rate mismatch for '%s'. Expected %.2f, found %.2f.",
                                            employeeName, expectedRate, actualRate)));
                        }
                    } catch (NumberFormatException e) {
                        issues.add(summaryIssue(
                                sessionId, "SM-03", "CRITICAL", rowIdx, 5, "Daily Rate",
                                String.format("Invalid Daily Rate value '%s' for '%s'.",
                                        dailyRateStr, employeeName)));
                    }
                }
            }

            // =========================================
            // SM-04 / FR-4: Billing Period Validation
            // =========================================
            // FR-4 Acceptance Criteria:
            // 1. Start Date and End Date must be present and match the dates in Project Mastersheet.
            // 2. Start Date must be before End Date.
            // 3. Dates must fall within active resource allocation period.
            // 4. Working Days must fall within billing period.
            if (!employeeName.isBlank()) {
                String normalizedName = employeeName.trim().toLowerCase();
                com.timesheet.validator.domain.Resource resource = resourceByName.get(normalizedName);

                // FR-4.1: Start/End dates are mandatory
                if (startDateStr.isBlank()) {
                    issues.add(summaryIssue(
                            sessionId, "SM-04", "CRITICAL", rowIdx, 6, "Start Date",
                            String.format("Start Date is mandatory for '%s'.", employeeName)));
                }
                if (endDateStr.isBlank()) {
                    issues.add(summaryIssue(
                            sessionId, "SM-04", "CRITICAL", rowIdx, 7, "End Date",
                            String.format("End Date is mandatory for '%s'.", employeeName)));
                }

                // Parse dates for further checks
                LocalDate parsedStart = startDateStr.isBlank() ? null : parseDate(startDateStr.trim());
                LocalDate parsedEnd = endDateStr.isBlank() ? null : parseDate(endDateStr.trim());

                // FR-4.2: Start Date must be before End Date
                if (parsedStart != null && parsedEnd != null && parsedStart.isAfter(parsedEnd)) {
                    issues.add(summaryIssue(
                            sessionId, "SM-04", "CRITICAL", rowIdx, 6, "Start Date",
                            String.format(
                                    "Start Date cannot be greater than End Date. Please check for '%s'.",
                                    employeeName)));
                }

                if (resource != null) {
                    // FR-4.1 (continued): Dates must match the Project Mastersheet
                    if (parsedStart != null && resource.getStartDate() != null
                            && !parsedStart.equals(resource.getStartDate())) {
                        issues.add(summaryIssue(
                                sessionId, "SM-04", "CRITICAL", rowIdx, 6, "Start Date",
                                String.format("Start Date mismatch for '%s'. Expected %s, found %s.",
                                        employeeName, resource.getStartDate(), startDateStr)));
                    }
                    if (parsedEnd != null && resource.getEndDate() != null
                            && !parsedEnd.equals(resource.getEndDate())) {
                        issues.add(summaryIssue(
                                sessionId, "SM-04", "CRITICAL", rowIdx, 7, "End Date",
                                String.format("End Date mismatch for '%s'. Expected %s, found %s.",
                                        employeeName, resource.getEndDate(), endDateStr)));
                    }

                    // FR-4.3: Dates must fall within active resource allocation period
                    if (parsedStart != null && resource.getStartDate() != null
                            && parsedStart.isBefore(resource.getStartDate())) {
                        issues.add(summaryIssue(
                                sessionId, "SM-04", "CRITICAL", rowIdx, 6, "Start Date",
                                String.format(
                                        "Start Date '%s' is before resource allocation start date %s for '%s'.",
                                        startDateStr, resource.getStartDate(), employeeName)));
                    }
                    if (parsedEnd != null && resource.getEndDate() != null
                            && parsedEnd.isAfter(resource.getEndDate())) {
                        issues.add(summaryIssue(
                                sessionId, "SM-04", "CRITICAL", rowIdx, 7, "End Date",
                                String.format(
                                        "End Date '%s' is after resource allocation end date %s for '%s'.",
                                        endDateStr, resource.getEndDate(), employeeName)));
                    }
                }

                // FR-4.4: Working Days must fall within billing period (sanity check)
                if (parsedStart != null && parsedEnd != null && !daysWorkedStr.isBlank()) {
                    try {
                        double summaryDays = Double.parseDouble(daysWorkedStr.trim());
                        long totalDaysInPeriod = ChronoUnit.DAYS.between(parsedStart, parsedEnd) + 1;
                        if (summaryDays > totalDaysInPeriod) {
                            issues.add(summaryIssue(
                                    sessionId, "SM-04", "CRITICAL", rowIdx, 8, "Days Worked",
                                    String.format(
                                            "Working Days (%.1f) exceed billing period length (%d days) for '%s'. " +
                                                    "Start=%s, End=%s",
                                            summaryDays, totalDaysInPeriod, employeeName,
                                            startDateStr, endDateStr)));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            // =========================================
            // SM-07: Working Days (three-way reconciliation)
            // =========================================
            if (!employeeName.isBlank() && !daysWorkedStr.isBlank()) {
                String normalizedName = employeeName.trim().toLowerCase();
                try {
                    double summaryDays = Double.parseDouble(daysWorkedStr.trim());

                    Double pivotDayVal = pivotDays.get(normalizedName);

                    Double timesheetTotal = timesheetTotals.get(normalizedName);
                    double workingHoursPerDay = getWorkingHoursPerDay(employeeName);
                    double expectedDaysFromTimesheet = (timesheetTotal != null)
                            ? timesheetTotal / workingHoursPerDay
                            : 0.0;

                    boolean mismatch = false;
                    StringBuilder msg = new StringBuilder();
                    msg.append(String.format("Working Days mismatch for '%s'. Summary=%.1f",
                            employeeName, summaryDays));

                    if (pivotDayVal != null && Math.abs(summaryDays - pivotDayVal) > 0.01) {
                        mismatch = true;
                        msg.append(String.format(", Pivot=%.1f", pivotDayVal));
                    }
                    if (Math.abs(summaryDays - expectedDaysFromTimesheet) > 0.01) {
                        mismatch = true;
                        msg.append(String.format(", Timesheet=%.1f", expectedDaysFromTimesheet));
                    }

                    if (mismatch) {
                        issues.add(summaryIssue(
                                sessionId, "SM-07", "CRITICAL", rowIdx, 8, "Days Worked",
                                msg.toString()));
                    }
                } catch (NumberFormatException e) {
                    issues.add(summaryIssue(
                            sessionId, "SM-07", "CRITICAL", rowIdx, 8, "Days Worked",
                            String.format("Invalid Days Worked value '%s' for '%s'.",
                                    daysWorkedStr, employeeName)));
                }
            }

            // =========================================
            // SM-08: Travel Expense sanity check
            // =========================================
            if (!travelExpenseStr.isBlank()) {
                try {
                    double travelExpense = Double.parseDouble(travelExpenseStr.trim());
                    if (travelExpense < 0) {
                        issues.add(summaryIssue(
                                sessionId, "SM-08", "CRITICAL", rowIdx, 9, "Travel Expense",
                                String.format("Travel Expense cannot be negative. Found %.2f for '%s'.",
                                        travelExpense, employeeName)));
                    }
                } catch (NumberFormatException e) {
                    issues.add(summaryIssue(
                            sessionId, "SM-08", "CRITICAL", rowIdx, 9, "Travel Expense",
                            String.format("Invalid Travel Expense value '%s' for '%s'.",
                                    travelExpenseStr, employeeName)));
                }
            }

            // =========================================
            // SM-09: Total Amount formula validation
            // =========================================
            if (!dailyRateStr.isBlank() && !daysWorkedStr.isBlank() && !totalAmountStr.isBlank()) {
                try {
                    double dailyRate = Double.parseDouble(dailyRateStr.trim());
                    double daysWorked = Double.parseDouble(daysWorkedStr.trim());
                    double travelExpense = travelExpenseStr.isBlank() ? 0.0
                            : Double.parseDouble(travelExpenseStr.trim());
                    double totalAmount = Double.parseDouble(totalAmountStr.trim());

                    double expectedTotal = dailyRate * daysWorked + travelExpense;

                    if (Math.abs(totalAmount - expectedTotal) > 0.01) {
                        issues.add(summaryIssue(
                                sessionId, "SM-09", "CRITICAL", rowIdx, 10, "Total Amount",
                                String.format(
                                        "Total Amount calculation mismatch for '%s'. Expected %.2f (%.2f * %.2f + %.2f), found %.2f.",
                                        employeeName, expectedTotal, dailyRate, daysWorked, travelExpense, totalAmount)));
                    }
                } catch (NumberFormatException e) {
                    issues.add(summaryIssue(
                            sessionId, "SM-09", "CRITICAL", rowIdx, 10, "Total Amount",
                            String.format("Invalid numeric value in Total Amount calculation for '%s'.",
                                    employeeName)));
                }
            }

            // =========================================
            // SM-10: Missing Daily Rate (Phase 4 / Bug 2.1)
            // =========================================
            if (!employeeName.isBlank() && dailyRateStr.isBlank()) {
                issues.add(summaryIssue(
                        sessionId, "SM-10", "CRITICAL", rowIdx, 5, "Daily Rate",
                        "Daily Rate is mandatory."));
            }

            // =========================================
            // SM-11: Missing Working Days (Phase 4 / Bug 2.2)
            // =========================================
            if (!employeeName.isBlank() && daysWorkedStr.isBlank()) {
                issues.add(summaryIssue(
                        sessionId, "SM-11", "CRITICAL", rowIdx, 8, "Days Worked",
                        "Working Days is mandatory."));
            }

            // =========================================
            // SM-12: Missing Total Amount (Phase 4 / Bug 2.3)
            // =========================================
            if (!employeeName.isBlank() && totalAmountStr.isBlank()) {
                issues.add(summaryIssue(
                        sessionId, "SM-12", "CRITICAL", rowIdx, 10, "Total Amount",
                        String.format(
                                "Total Amount is mandatory and missing for employee '%s'.",
                                employeeName)));
            }
        }

        log.info("Summary Validation completed.");
    }




    // ======================================================
    // COMMERCIAL VALIDATION
    // ======================================================

    /**
     * Build a ValidationIssue for the Commercial sheet.
     */
    private ValidationIssue commercialIssue(
            String sid,
            String ruleId,
            String severity,
            int row,
            int col,
            String field,
            String msg) {

        return ValidationIssue.builder()
                .sessionId(sid)
                .ruleId(ruleId)
                .severity(severity)
                .sheetName(COMMERCIAL_SHEET)
                .rowIdx(row)
                .colIdx(col)
                .fieldName(field)
                .message(renderMessage(ruleId, severity, field, msg))
                .build();
    }


    /**
     * Validates the Commercial sheet against master data and Summary sheet.
     *
     * Commercial sheet layout (0-indexed rowIdx):
     *   Row 0: Project Name | value
     *   Row 1: Project ID   | value
     *   Row 2: PO Number    | value
     *   Row 3: PO Value     | value
     *   Row 4: Total Billable Headcount | value
     *   Row 5: Month        | date serial
     *   Row 6: Month Ideal Days | value
     *   Row 7: PO Balance   | value
     *   Row 8: Data header: Work Location | Resource count | Total Billable Days | Total Billable Amount
     *   Row 9+: Data rows
     *   Row 13: Invoicing plan header
     *   Row 15: PO # | value
     *   Row 16: PO Amount | value
     *   Row 17: Invoicing header: Month | Planned Value | Actual Value | PO Balance | Remarks
     *   Row 18+: Invoicing data rows
     *
     * Column layout (0-indexed):
     *   Col 0: Label
     *   Col 1: Value (or Resource count in data section)
     *   Col 2: Total Billable Days (data section)
     *   Col 3: Total Billable Amount (data section)
     *   Col 4: Remarks (invoicing section)
     */
    private void validateCommercial(
            String sessionId,
            List<CellData> timesheetCells,
            List<CellData> summaryCells,
            List<CellData> commercialCells,
            List<ValidationIssue> issues) {

        log.info("==================================================");
        log.info("Starting Commercial Validation");
        log.info("==================================================");

        // Build row map for Commercial sheet
        TreeMap<Integer, Map<Integer, CellData>> commercialRowMap = new TreeMap<>();
        for (CellData c : commercialCells) {
            commercialRowMap.computeIfAbsent(c.getRowIdx(), k -> new TreeMap<>()).put(c.getColIdx(), c);
        }

        if (commercialRowMap.isEmpty()) {
            log.warn("Commercial sheet is empty.");
            return;
        }

        // Helper to get value from a specific row/col
        // Col 0 = label, Col 1 = value
        java.util.function.BiFunction<Integer, Integer, String> getValue = (rowIdx, colIdx) -> {
            Map<Integer, CellData> row = commercialRowMap.get(rowIdx);
            if (row == null) return "";
            CellData cell = row.get(colIdx);
            if (cell == null) return "";
            String display = cell.getDisplayValue();
            return display == null ? "" : display.trim();
        };

        // Preload SOW master data
        Map<String, com.timesheet.validator.domain.SowMaster> sowByNumber = new HashMap<>();
        sowMasterRepo.findAll().forEach(s -> sowByNumber.put(s.getSowNumber(), s));

        // Preload resource data
        Map<String, com.timesheet.validator.domain.Resource> resourceByName = new HashMap<>();
        resourceRepo.findAll().forEach(r -> resourceByName.put(r.getName().trim().toLowerCase(), r));

        // =========================================
        // =========================================
        // Extract values from Commercial sheet
        // =========================================
        String projectName = getValue.apply(0, 1);
        String projectId = getValue.apply(1, 1);
        String poNumber = getValue.apply(2, 1);
        String poValueStr = getValue.apply(3, 1);
        String billableHeadcountStr = getValue.apply(4, 1);
        String poBalanceStr = getValue.apply(7, 1);
        String poAmountStr = getValue.apply(15, 1);

        // Normalize PO number (may appear as scientific notation)
        String normalizedPoNumber = poNumber;
        if (normalizedPoNumber.contains("E") || normalizedPoNumber.contains("e")) {
            try {
                normalizedPoNumber = String.valueOf((long) Double.parseDouble(normalizedPoNumber));
            } catch (NumberFormatException ignored) {}
        }

        // =========================================
        // CM-01: Project Information Validation
        // =========================================
        log.info("CM-01: Project Name='{}' Project ID='{}'", projectName, projectId);

        if (projectName.isBlank()) {
            issues.add(commercialIssue(
                    sessionId, "CM-01", "CRITICAL", 0, 1, "Project Name",
                    "Project Name is mandatory. Project Name not found in Project Master."));
        } else {
            // Check if any SOW has a description matching the project name
            boolean projectFound = false;
            for (com.timesheet.validator.domain.SowMaster sow : sowByNumber.values()) {
                if (sow.getDescription() != null
                        && sow.getDescription().trim().equalsIgnoreCase(projectName.trim())) {
                    projectFound = true;
                    break;
                }
            }
            if (!projectFound) {
                issues.add(commercialIssue(
                        sessionId, "CM-01", "CRITICAL", 0, 1, "Project Name",
                        String.format("Invalid Project Name. Project '%s' not found in Project Master.", projectName)));
            }
        }

        if (projectId.isBlank()) {
            issues.add(commercialIssue(
                    sessionId, "CM-01", "CRITICAL", 1, 1, "Project ID",
                    "Project ID is mandatory. Project ID not found in Project Master."));
        } else {
            // Check if any SOW has a sowNumber containing the project ID info
            // Project ID format: "IGT SOW No 18-2026" — we check if it matches any SOW
            boolean idFound = false;
            for (com.timesheet.validator.domain.SowMaster sow : sowByNumber.values()) {
                String combined = sow.getClient() + " SOW No " + sow.getSowNumber().replace("SOW_", "").replace("_", "-");
                if (combined.equalsIgnoreCase(projectId.trim())) {
                    idFound = true;
                    break;
                }
            }
            if (!idFound) {
                issues.add(commercialIssue(
                        sessionId, "CM-01", "CRITICAL", 1, 1, "Project ID",
                        String.format("Invalid Project ID. Project ID '%s' not found in Project Master.", projectId)));
            }
        }

        // =========================================
        // CM-02: PO Validation & Resource Count Validation
        // =========================================
        log.info("CM-02: PO Number='{}' PO Value='{}' Headcount='{}'",
                normalizedPoNumber, poValueStr, billableHeadcountStr);

        // Validate PO Number against SOW_MASTER
        if (normalizedPoNumber.isBlank()) {
            issues.add(commercialIssue(
                    sessionId, "CM-02", "CRITICAL", 2, 1, "PO Number",
                    "PO Number is mandatory. PO Number missing for selected Project."));
        } else {
            boolean poFound = false;
            String expectedPoValue = null;
            for (com.timesheet.validator.domain.SowMaster sow : sowByNumber.values()) {
                if (sow.getPoNumber() != null && sow.getPoNumber().equals(normalizedPoNumber)) {
                    poFound = true;
                    expectedPoValue = sow.getPoValue() != null
                            ? String.valueOf(sow.getPoValue().longValue())
                            : null;
                    break;
                }
            }
            if (!poFound) {
                issues.add(commercialIssue(
                        sessionId, "CM-02", "CRITICAL", 2, 1, "PO Number",
                        String.format("Invalid PO Number '%s' for selected Project.", normalizedPoNumber)));
            } else if (expectedPoValue != null && !poValueStr.isBlank()) {
                // Validate PO Value against the matched master PO (blank handled below)
                try {
                    String normalizedPoValue = poValueStr.replaceAll("[,$]", "");
                    if (!normalizedPoValue.equals(expectedPoValue)) {
                        issues.add(commercialIssue(
                                sessionId, "CM-02", "CRITICAL", 3, 1, "PO Value",
                                String.format("PO Value mismatch with Project Mastersheet. Expected '%s', found '%s'.",
                                        expectedPoValue, poValueStr)));
                    }
                } catch (Exception e) {
                    log.warn("Could not parse PO Value: {}", poValueStr);
                }
            }
        }

        // PO Value is mandatory regardless of whether the PO Number is blank or
        // invalid (FR-2: PO Value must match the master / must not be missing).
        if (poValueStr.isBlank()) {
            issues.add(commercialIssue(
                    sessionId, "CM-02", "CRITICAL", 3, 1, "PO Value",
                    "PO Value is mandatory. PO Value missing for selected Project."));
        }

        // Validate Total Billable Headcount against Summary resource count
        if (billableHeadcountStr.isBlank()) {
            issues.add(commercialIssue(
                    sessionId, "CM-02", "CRITICAL", 4, 1, "Total Billable Headcount",
                    "Total Billable Headcount is mandatory. Resource count missing."));
        } else if (summaryCells != null && !summaryCells.isEmpty()) {
            try {
                int commercialHeadcount = Integer.parseInt(billableHeadcountStr.trim());

                // Count unique resources in Summary sheet
                Set<String> summaryResources = new HashSet<>();
                TreeMap<Integer, Map<Integer, CellData>> summaryRowMap = new TreeMap<>();
                for (CellData c : summaryCells) {
                    summaryRowMap.computeIfAbsent(c.getRowIdx(), k -> new TreeMap<>()).put(c.getColIdx(), c);
                }
                int summaryFirstKey = summaryRowMap.firstKey();
                int summaryLastKey = summaryRowMap.lastKey();
                int dataStartRow = summaryFirstKey + 2;

                for (Map.Entry<Integer, Map<Integer, CellData>> entry : summaryRowMap.entrySet()) {
                    int rowIdx = entry.getKey();
                    if (rowIdx < dataStartRow) continue;
                    if (rowIdx == summaryLastKey) continue;
                    Map<Integer, CellData> cols = entry.getValue();
                    String empName = val(cols, 3);
                    if (!empName.isBlank()) {
                        summaryResources.add(empName.trim().toLowerCase());
                    }
                }

                if (commercialHeadcount != summaryResources.size()) {
                    issues.add(commercialIssue(
                            sessionId, "CM-02", "CRITICAL", 4, 1, "Total Billable Headcount",
                            String.format("Resource count mismatch detected. Commercial=%d, Summary=%d.",
                                    commercialHeadcount, summaryResources.size())));
                }
            } catch (NumberFormatException e) {
                issues.add(commercialIssue(
                        sessionId, "CM-02", "CRITICAL", 4, 1, "Total Billable Headcount",
                        String.format("Invalid Total Billable Headcount value: '%s'.", billableHeadcountStr)));
            }
        }

        // =========================================
        // CM-03: Total Billable Days Validation
        // =========================================
        log.info("CM-03: Checking Total Billable Days against Summary");

        if (summaryCells != null && !summaryCells.isEmpty()) {
            // Calculate total Summary working days
            double summaryTotalDays = 0;
            TreeMap<Integer, Map<Integer, CellData>> summaryRowMap = new TreeMap<>();
            for (CellData c : summaryCells) {
                summaryRowMap.computeIfAbsent(c.getRowIdx(), k -> new TreeMap<>()).put(c.getColIdx(), c);
            }
            int sFirstKey = summaryRowMap.firstKey();
            int sLastKey = summaryRowMap.lastKey();
            int sDataStart = sFirstKey + 2;

            for (Map.Entry<Integer, Map<Integer, CellData>> entry : summaryRowMap.entrySet()) {
                int rowIdx = entry.getKey();
                if (rowIdx < sDataStart) continue;
                if (rowIdx == sLastKey) continue;
                Map<Integer, CellData> cols = entry.getValue();
                String daysStr = val(cols, 8);
                if (!daysStr.isBlank()) {
                    try {
                        summaryTotalDays += Double.parseDouble(daysStr.trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Commercial data section: rows 9..11 hold per-location rows followed by
            // the project totals in the last row. Use the last row's Total Billable
            // Days as the project total (mirrors the Summary sheet totals row).
            double commercialTotalDays = Double.NaN;
            int commercialDaysRow = -1;
            for (Map.Entry<Integer, Map<Integer, CellData>> entry : commercialRowMap.entrySet()) {
                int rowIdx = entry.getKey();
                // Data rows start at row 9 (0-indexed), before invoicing plan section
                if (rowIdx < 9) continue;
                // Stop at invoicing plan section (row 12+ is the invoicing plan)
                if (rowIdx >= 12) continue;
                Map<Integer, CellData> cols = entry.getValue();
                // Col 2 = Total Billable Days
                String daysStr = val(cols, 2);
                if (!daysStr.isBlank()) {
                    try {
                        commercialTotalDays = Double.parseDouble(daysStr.trim());
                        commercialDaysRow = rowIdx;
                    } catch (NumberFormatException ignored) {}
                }
            }

            log.info("CM-03: Summary total days={}, Commercial total days={}",
                    summaryTotalDays, commercialTotalDays);

            if (!Double.isNaN(commercialTotalDays) && commercialTotalDays < 0) {
                issues.add(commercialIssue(
                        sessionId, "CM-03", "CRITICAL", commercialDaysRow, 2, "Total Billable Days",
                        String.format("Total Billable Days cannot be negative. Found %.1f.", commercialTotalDays)));
            }

            if (!Double.isNaN(commercialTotalDays) && summaryTotalDays > 0
                    && Math.abs(commercialTotalDays - summaryTotalDays) > 0.01) {
                issues.add(commercialIssue(
                        sessionId, "CM-03", "CRITICAL", 8, 2, "Total Billable Days",
                        String.format("Total Billable Days mismatch between Summary and Commercial sheet. Summary=%.1f, Commercial=%.1f.",
                                summaryTotalDays, commercialTotalDays)));
            }
        }

        // =========================================
        // CM-04: Total Billable Amount Validation
        // =========================================
        log.info("CM-04: Checking Total Billable Amount against Summary");

        if (summaryCells != null && !summaryCells.isEmpty()) {
            // Calculate total Summary amount
            double summaryTotalAmount = 0;
            TreeMap<Integer, Map<Integer, CellData>> summaryRowMap = new TreeMap<>();
            for (CellData c : summaryCells) {
                summaryRowMap.computeIfAbsent(c.getRowIdx(), k -> new TreeMap<>()).put(c.getColIdx(), c);
            }
            int sFirstKey = summaryRowMap.firstKey();
            int sLastKey = summaryRowMap.lastKey();
            int sDataStart = sFirstKey + 2;

            for (Map.Entry<Integer, Map<Integer, CellData>> entry : summaryRowMap.entrySet()) {
                int rowIdx = entry.getKey();
                if (rowIdx < sDataStart) continue;
                if (rowIdx == sLastKey) continue;
                Map<Integer, CellData> cols = entry.getValue();
                String amountStr = val(cols, 10);
                if (!amountStr.isBlank()) {
                    try {
                        summaryTotalAmount += Double.parseDouble(amountStr.trim().replaceAll("[,$]", ""));
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Commercial data section: use the last data row's Total Billable Amount
            // as the project total (mirrors the Summary sheet totals row).
            double commercialTotalAmount = Double.NaN;
            int commercialAmountRow = -1;
            for (Map.Entry<Integer, Map<Integer, CellData>> entry : commercialRowMap.entrySet()) {
                int rowIdx = entry.getKey();
                if (rowIdx < 9) continue;
                if (rowIdx >= 12) continue;
                Map<Integer, CellData> cols = entry.getValue();
                // Col 3 = Total Billable Amount
                String amountStr = val(cols, 3);
                if (!amountStr.isBlank()) {
                    try {
                        commercialTotalAmount = Double.parseDouble(amountStr.trim().replaceAll("[,$]", ""));
                        commercialAmountRow = rowIdx;
                    } catch (NumberFormatException ignored) {}
                }
            }

            log.info("CM-04: Summary total amount={}, Commercial total amount={}",
                    summaryTotalAmount, commercialTotalAmount);

            if (!Double.isNaN(commercialTotalAmount) && commercialTotalAmount < 0) {
                issues.add(commercialIssue(
                        sessionId, "CM-04", "CRITICAL", commercialAmountRow, 3, "Total Billable Amount",
                        String.format("Total Billable Amount cannot be negative. Found %.2f.", commercialTotalAmount)));
            }

            if (!Double.isNaN(commercialTotalAmount) && summaryTotalAmount > 0
                    && Math.abs(commercialTotalAmount - summaryTotalAmount) > 0.01) {
                issues.add(commercialIssue(
                        sessionId, "CM-04", "CRITICAL", 8, 3, "Total Billable Amount",
                        String.format("Total Billable Amount mismatch between Summary and Commercial sheet. Summary=%.2f, Commercial=%.2f.",
                                summaryTotalAmount, commercialTotalAmount)));
            }
        }

        // =========================================
        // CM-05: Planned Value, Actual Value & PO Balance Validation
        // =========================================
        log.info("CM-05: Checking PO Balance calculation");

        if (!poAmountStr.isBlank() && !poBalanceStr.isBlank()) {
            try {
                double poAmount = Double.parseDouble(poAmountStr.trim().replaceAll("[,$]", ""));
                double poBalance = Double.parseDouble(poBalanceStr.trim().replaceAll("[,$]", ""));

                // Sum all Actual Values from invoicing plan rows (row 17+)
                double cumulativeActualValue = 0;
                for (Map.Entry<Integer, Map<Integer, CellData>> entry : commercialRowMap.entrySet()) {
                    int rowIdx = entry.getKey();
                    // Invoicing data rows start at row 17 (0-indexed)
                    if (rowIdx < 17) continue;
                    Map<Integer, CellData> cols = entry.getValue();
                    // Col 2 = Actual Value in invoicing section
                    String actualStr = val(cols, 2);
                    if (!actualStr.isBlank()) {
                        try {
                            cumulativeActualValue += Double.parseDouble(actualStr.trim().replaceAll("[,$]", ""));
                        } catch (NumberFormatException ignored) {}
                    }
                }

                // Expected PO Balance = PO Amount - Cumulative Actual Value
                double expectedBalance = poAmount - cumulativeActualValue;

                log.info("CM-05: PO Amount={}, Cumulative Actual={}, Expected Balance={}, Actual Balance={}",
                        poAmount, cumulativeActualValue, expectedBalance, poBalance);

                if (Math.abs(expectedBalance - poBalance) > 0.01) {
                    issues.add(commercialIssue(
                            sessionId, "CM-05", "CRITICAL", 7, 1, "PO Balance",
                            String.format("PO Balance calculation mismatch. Expected=%.2f (PO Amount %.2f - Cumulative Actual %.2f), found %.2f.",
                                    expectedBalance, poAmount, cumulativeActualValue, poBalance)));
                }

                // Validate each invoicing row: Planned Value and Actual Value are
                // mandatory for every month in the invoicing plan (FR-5).
                for (Map.Entry<Integer, Map<Integer, CellData>> entry : commercialRowMap.entrySet()) {
                    int rowIdx = entry.getKey();
                    if (rowIdx < 17) continue;
                    Map<Integer, CellData> cols = entry.getValue();
                    String plannedStr = val(cols, 1);
                    String actualStr = val(cols, 2);
                    String balanceStr = val(cols, 3);
                    if (plannedStr.isBlank() && actualStr.isBlank() && balanceStr.isBlank()) continue;

                    if (plannedStr.isBlank()) {
                        issues.add(commercialIssue(
                                sessionId, "CM-05", "CRITICAL", rowIdx, 1, "Planned Value",
                                String.format("Planned Value is mandatory for invoicing row %d.", rowIdx + 1)));
                    }
                    if (actualStr.isBlank()) {
                        issues.add(commercialIssue(
                                sessionId, "CM-05", "CRITICAL", rowIdx, 2, "Actual Value",
                                String.format("Actual Value is mandatory for invoicing row %d.", rowIdx + 1)));
                    }
                }

            } catch (NumberFormatException e) {
                log.warn("CM-05: Could not parse numeric values: PO Amount='{}', PO Balance='{}'",
                        poAmountStr, poBalanceStr);
            }
        }

        // =========================================
        // CM-06: Positive PO Balance Validation
        // =========================================
        log.info("CM-06: Checking PO Balance is positive");

        if (!poBalanceStr.isBlank()) {
            try {
                double poBalance = Double.parseDouble(poBalanceStr.trim().replaceAll("[,$]", ""));
                if (poBalance < 0) {
                    issues.add(commercialIssue(
                            sessionId, "CM-06", "WARNING", 7, 1, "PO Balance",
                            String.format("Warning: PO Balance has turned negative (%.2f). Project has exceeded allocated budget.",
                                    poBalance)));
                }

                // Also check invoicing plan rows for negative PO Balance
                for (Map.Entry<Integer, Map<Integer, CellData>> entry : commercialRowMap.entrySet()) {
                    int rowIdx = entry.getKey();
                    if (rowIdx < 17) continue;
                    Map<Integer, CellData> cols = entry.getValue();
                    String balanceStr = val(cols, 3);
                    if (balanceStr.isBlank()) continue;
                    try {
                        double invBalance = Double.parseDouble(balanceStr.trim().replaceAll("[,$]", ""));
                        if (invBalance < 0) {
                            issues.add(commercialIssue(
                                    sessionId, "CM-06", "WARNING", rowIdx, 3, "PO Balance",
                                    String.format("Warning: PO Balance has turned negative (%.2f) in invoicing row %d. Project has exceeded allocated budget.",
                                            invBalance, rowIdx + 1)));
                        }
                    } catch (NumberFormatException ignored) {}
                }

            } catch (NumberFormatException e) {
                log.warn("CM-06: Could not parse PO Balance: '{}'", poBalanceStr);
            }
        }

        // =========================================
        // CM-07: Actual Value vs Total Billable Amount Validation
        // =========================================
        log.info("CM-07: Comparing Actual Value against Total Billable Amount");

        if (summaryCells != null && !summaryCells.isEmpty()) {
            // The true Total Billable Amount is derived from the Summary sheet
            // (what the current reporting month should actually be billed).
            double summaryTotalAmount = 0;
            TreeMap<Integer, Map<Integer, CellData>> summaryRowMap = new TreeMap<>();
            for (CellData c : summaryCells) {
                summaryRowMap.computeIfAbsent(c.getRowIdx(), k -> new TreeMap<>()).put(c.getColIdx(), c);
            }
            int sFirstKey = summaryRowMap.firstKey();
            int sLastKey = summaryRowMap.lastKey();
            int sDataStart = sFirstKey + 2;

            for (Map.Entry<Integer, Map<Integer, CellData>> entry : summaryRowMap.entrySet()) {
                int rowIdx = entry.getKey();
                if (rowIdx < sDataStart) continue;
                if (rowIdx == sLastKey) continue;
                Map<Integer, CellData> cols = entry.getValue();
                String amountStr = val(cols, 10);
                if (!amountStr.isBlank()) {
                    try {
                        summaryTotalAmount += Double.parseDouble(amountStr.trim().replaceAll("[,$]", ""));
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (summaryTotalAmount > 0) {
                // The reporting month is the Commercial header month (row 5, col 1).
                String reportingMonth = "";
                Map<Integer, CellData> monthRow = commercialRowMap.get(5);
                if (monthRow != null && monthRow.get(1) != null) {
                    String raw = monthRow.get(1).getRawValue();
                    if (raw != null && raw.length() >= 7) reportingMonth = raw.substring(0, 7);
                }

                // Locate the invoicing row matching the reporting month.
                int matchedRow = -1;
                String actualStr = "";
                for (Map.Entry<Integer, Map<Integer, CellData>> entry : commercialRowMap.entrySet()) {
                    int rowIdx = entry.getKey();
                    if (rowIdx < 17) continue; // invoicing data rows
                    Map<Integer, CellData> cols = entry.getValue();
                    CellData monthCell = cols.get(0);
                    boolean matches = false;
                    if (!reportingMonth.isBlank() && monthCell != null && monthCell.getRawValue() != null
                            && monthCell.getRawValue().length() >= 7) {
                        matches = monthCell.getRawValue().substring(0, 7).equals(reportingMonth);
                    }
                    if (matches) {
                        matchedRow = rowIdx;
                        actualStr = val(cols, 2);
                        break;
                    }
                }

                // Fallback: top-most invoicing data row with a non-blank Actual Value
                // (used when the reporting month cannot be matched explicitly).
                if (matchedRow < 0) {
                    for (Map.Entry<Integer, Map<Integer, CellData>> entry : commercialRowMap.entrySet()) {
                        int rowIdx = entry.getKey();
                        if (rowIdx < 17) continue;
                        Map<Integer, CellData> cols = entry.getValue();
                        String monthStr = val(cols, 0);
                        if (monthStr.isBlank()) continue; // skip blank/header-like rows
                        String a = val(cols, 2);
                        if (!a.isBlank()) {
                            matchedRow = rowIdx;
                            actualStr = a;
                            break;
                        }
                    }
                }

                if (matchedRow >= 0 && !actualStr.isBlank()) {
                    try {
                        double actualValue = Double.parseDouble(actualStr.trim().replaceAll("[,$]", ""));
                        if (Math.abs(actualValue - summaryTotalAmount) > 0.01) {
                            issues.add(commercialIssue(
                                    sessionId, "CM-07", "CRITICAL", matchedRow, 2, "Actual Value",
                                    String.format("Actual Value does not match Total Billable amount. " +
                                            "Expected %.2f (Total Billable Amount), found %.2f.",
                                            summaryTotalAmount, actualValue)));
                        }
                    } catch (NumberFormatException e) {
                        log.warn("CM-07: Could not parse Actual Value '{}'", actualStr);
                    }
                }
            }
        }

        log.info("Commercial Validation completed.");
    }


}
