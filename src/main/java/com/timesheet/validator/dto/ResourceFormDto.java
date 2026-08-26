package com.timesheet.validator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceFormDto {

    // ============================================================
    // RESOURCE
    // ============================================================

    private Long id;

    private String resourceId;

    private String name;

    private String location;

    private String company;

    private BigDecimal dailyRateUsd;

    private Double workingHoursPerDay;

    private LocalDate startDate;

    private LocalDate endDate;


    // ============================================================
    // RESOURCE_SOW
    // ============================================================

    private String assignedTeam;

    private String subProject;

    private String projectCode;

//    private String travelExpense;

    private BigDecimal travelExpense;

    private String sowNumber;

    private String roleInSow;


    // ============================================================
    // SOW_MASTER
    // ============================================================

    private String project;

    private String projectLocation;

    private String sowDescription;

    private LocalDate sowStartDate;

    private LocalDate sowEndDate;


    // ============================================================
    // PO_MASTER
    // ============================================================

    private String poNumber;

    private String updatedPoNumber;

    private BigDecimal poValue;

    private LocalDate poStartDate;

    private LocalDate poEndDate;
}