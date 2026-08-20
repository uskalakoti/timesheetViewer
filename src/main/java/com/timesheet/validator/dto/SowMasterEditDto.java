package com.timesheet.validator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SowMasterEditDto {

    // =========================
    // SOW_MASTER
    // =========================

    private Long id;

    private String sowNumber;

    private String project;

    private String projectLocation;

    private String description;

    private LocalDate startDate;

    private LocalDate endDate;

    private Boolean active;


    // =========================
    // PO_MASTER
    // =========================

    private String poNumber;

    private String updatedPoNumber;

    private BigDecimal poValue;

    private LocalDate poStartDate;

    private LocalDate poEndDate;
}
