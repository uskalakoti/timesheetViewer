package com.timesheet.validator.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * CR 5.1 — Project/template selection offered before timesheet upload.
 *
 * <p>The selected project determines which processing pipeline runs:</p>
 * <ul>
 *   <li>{@link #SYDNEY_SOFTDEV} — the natively supported Sydney SoftDev
 *       timesheet structure; processed as-is.</li>
 *   <li>{@link #GENERALIZED_TIMESHEET} — a generalized layout that is first
 *       transformed into the Sydney SoftDev format by the transformation
 *       engine (CR 5.3) before parsing and validation.</li>
 * </ul>
 */
public enum UploadProject {

    SYDNEY_SOFTDEV("Sydney SoftDev", Set.of()),

    /**
     * Source layout lacks Assigned Team (col 2), Sub Project (col 4),
     * Project Code (col 5) and Country/Location (col 6). Those columns are
     * excluded from mandatory-field validation (TS-08) and rendered as
     * "Not Applicable" by the viewer.
     */
    GENERALIZED_TIMESHEET("Generalized Timesheet", Set.of(2, 4, 5, 6));

    private final String label;

    /**
     * Timesheet-sheet column indexes (Sydney SoftDev layout, 0-based:
     * 0=Date, 1=Name, 2=Assigned Team, 3=Project, 4=Sub Project,
     * 5=Project Code, 6=Country, 7=Hours, 8=Task, 9=Company, 10=SOW)
     * that this format does not carry.
     */
    private final Set<Integer> notApplicableTimesheetColumns;

    UploadProject(String label, Set<Integer> notApplicableTimesheetColumns) {
        this.label = label;
        this.notApplicableTimesheetColumns = notApplicableTimesheetColumns;
    }

    public String getLabel() {
        return label;
    }

    /** Columns of the normalized Timesheet sheet that are not applicable to this format. */
    public Set<Integer> getNotApplicableTimesheetColumns() {
        return notApplicableTimesheetColumns;
    }

    /** Null-safe accessor used by services holding only the persisted string value. */
    public static Set<Integer> naTimesheetColumnsOf(String rawValue) {
        UploadProject project = fromParam(rawValue);
        return project != null ? project.getNotApplicableTimesheetColumns() : Set.of();
    }

    /** Resolves a request parameter value, case-insensitively; {@code null} when unknown. */
    public static UploadProject fromParam(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns {@code true} when the upload format is {@link #GENERALIZED_TIMESHEET}
     * and the entry is a structural weekend placeholder (zero hours on Saturday or
     * Sunday). Such rows are an accepted artifact of the Timatic export and should
     * not trigger TS-02 (weekend) or TS-04 (hours must be positive) violations.
     *
     * @param rawProject the persisted upload project string (safe to pass {@code null})
     * @param hours      the row's parsed hours value
     * @param date       the row's parsed date ({@code null} → returns {@code false})
     * @return {@code true} if the checks should be suppressed for this row
     */
    public static boolean isStructuralWeekendZero(String rawProject, double hours, LocalDate date) {
        if (fromParam(rawProject) != GENERALIZED_TIMESHEET || date == null || hours != 0.0) {
            return false;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /**
     * Defect 7.2 — Returns {@code true} when the upload format is
     * {@link #GENERALIZED_TIMESHEET} and the entry has zero hours on a date
     * that is a known public holiday. Such entries are an accepted artifact
     * of the Timatic calendar-based export and should not trigger TS-03
     * (public holiday) or TS-04 (hours must be positive) violations.
     *
     * @param rawProject the persisted upload project string
     * @param hours      the row's parsed hours value
     * @param date       the row's parsed date
     * @param holidays   set of known public holiday dates (pass empty set if unknown)
     * @return {@code true} if the checks should be suppressed for this row
     */
    public static boolean isStructuralHolidayZero(String rawProject, double hours, LocalDate date,
                                                   java.util.Set<java.time.LocalDate> holidays) {
        if (fromParam(rawProject) != GENERALIZED_TIMESHEET || date == null || hours != 0.0) {
            return false;
        }
        return holidays.contains(date);
    }

    /**
     * Defect 7.2 — Returns {@code true} when the upload format is
     * {@link #GENERALIZED_TIMESHEET} and the entry has zero hours with a
     * task/description indicating planned leave (e.g. "PL", "Planned Leave").
     * Such entries are an accepted artifact of the Timatic export and should
     * not trigger TS-04 (hours must be positive) violations.
     *
     * @param rawProject the persisted upload project string
     * @param hours      the row's parsed hours value
     * @param task       the row's task/description value from the Timesheet
     * @return {@code true} if the checks should be suppressed for this row
     */
    public static boolean isStructuralPlannedLeaveZero(String rawProject, double hours, String task) {
        if (fromParam(rawProject) != GENERALIZED_TIMESHEET || hours != 0.0) {
            return false;
        }
        if (task == null || task.isBlank()) {
            return false;
        }
        String upper = task.trim().toUpperCase();
        return upper.equals("PL") || upper.contains("PLANNED LEAVE") || upper.contains("PL ");
    }
}
