package com.timesheet.validator.domain;

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

    SYDNEY_SOFTDEV("Sydney SoftDev"),
    GENERALIZED_TIMESHEET("Generalized Timesheet");

    private final String label;

    UploadProject(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
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
