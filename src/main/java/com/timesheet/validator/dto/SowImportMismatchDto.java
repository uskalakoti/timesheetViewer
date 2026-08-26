package com.timesheet.validator.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SowImportMismatchDto {

    /*
     * Primary business identifier for SOW Master.
     */
    private String sowNumber;

    /*
     * Name of the field whose value changed.
     *
     * Examples:
     * Project
     * Project Location
     * SOW Description
     * PO Number
     * Updated PO Number
     * PO Start Date
     * PO End Date
     * PO Value
     */
    private String fieldName;

    /*
     * Current value stored in Master Data.
     */
    private String existingValue;

    /*
     * Value coming from the newly uploaded SOW Master file.
     */
    private String uploadedValue;
}