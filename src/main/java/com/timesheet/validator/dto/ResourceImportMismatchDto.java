package com.timesheet.validator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceImportMismatchDto {

    /**
     * Employee ID is the primary business key
     * used for Resource Master validation.
     */
    private String employeeId;

    /**
     * Name of the field whose mapping has changed.
     */
    private String fieldName;

    /**
     * Existing value currently present in Master Data.
     */
    private String existingValue;

    /**
     * Value coming from the newly uploaded file.
     */
    private String uploadedValue;
}