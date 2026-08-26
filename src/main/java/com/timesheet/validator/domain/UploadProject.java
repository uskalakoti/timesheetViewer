package com.timesheet.validator.domain;

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
}
