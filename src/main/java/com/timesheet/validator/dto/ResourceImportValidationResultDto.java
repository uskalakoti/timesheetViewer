package com.timesheet.validator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceImportValidationResultDto {

    /**
     * True when at least one Employee ID mapping
     * discrepancy has been detected.
     */
    private boolean hasMismatches;

    /**
     * All detected mismatches.
     *
     * Multiple entries can belong to the same Employee ID.
     */
    private List<ResourceImportMismatchDto> mismatches;
}