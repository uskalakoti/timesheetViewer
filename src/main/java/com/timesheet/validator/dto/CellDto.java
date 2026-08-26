package com.timesheet.validator.dto;

import lombok.*;
import java.util.List;

/** One cell in the rendered grid */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CellDto {
    private int rowIdx;
    private int colIdx;
    private String displayValue;
    private String formula;        // null when no formula
    private String cellType;
    private boolean isHeader;
//    private String validationMsg;  // non-null = this cell has a validation issue
//    private String severity;       // CRITICAL | WARNING

    private List<String> validationMessages;
    private List<String> severities;
    private String highestSeverity;
    private boolean employeeIssue;

    /** True when this field does not exist in the uploaded format (rendered muted). */
    private Boolean notApplicable;

    /*
     * Excel styling information.
     */
    private String backgroundColor;

    private String fontColor;

    private Short fontSize;

    private boolean bold;

    private boolean italic;

    private String horizontalAlignment;

    private String verticalAlignment;

    private String borderTop;

    private String borderBottom;

    private String borderLeft;

    private String borderRight;
}
