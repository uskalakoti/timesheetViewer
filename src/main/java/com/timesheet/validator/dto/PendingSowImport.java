package com.timesheet.validator.dto;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingSowImport {

    /*
     * The actual SOW records parsed from the uploaded workbook.
     *
     * These records must NOT be persisted until the user
     * explicitly clicks Proceed.
     */
    private List<SowImportDto> sows;

    /*
     * Field-level discrepancies detected during validation.
     *
     * This is used by the review page.
     */
    private SowImportValidationResultDto validationResult;
}