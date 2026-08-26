package com.timesheet.validator.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SowImportDto {

    private String project;

    private String projectLocation;

    private String sowNumber;

    private String sowDescription;

    private String poNumber;

    private String updatedPoNumber;

    private LocalDate sowStartDate;

    private LocalDate sowEndDate;

    private LocalDate poStartDate;

    private LocalDate poEndDate;

    private BigDecimal poValue;
}