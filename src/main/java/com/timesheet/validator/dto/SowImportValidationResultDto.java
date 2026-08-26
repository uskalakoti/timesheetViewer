package com.timesheet.validator.dto;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SowImportValidationResultDto {

    /*
     * Indicates whether any existing SOW mapping
     * has changed.
     */
    private boolean hasMismatches;

    /*
     * All detected field-level mismatches.
     *
     * Multiple mismatches for the same SOW Number
     * are stored in this list and can later be
     * grouped on the review page.
     */
    private List<SowImportMismatchDto> mismatches;
}