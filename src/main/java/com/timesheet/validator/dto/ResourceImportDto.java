package com.timesheet.validator.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceImportDto {

    // Employee Information
    private String resourceId;          // Employee ID
    private String employeeName;
    private String location;
    private String company;

    // Assignment Information
    private String assignedTeam;
    private String project;
    private String subProject;
    private String projectCode;

    // SOW Information
    private String sowNumber;
    private String roleInSow;
    private String sowDescription;

    // PO Information
    private String poNumber;
    private String updatedPoNumber;

    // Financial Information
    private BigDecimal dailyRateUsd;
    private BigDecimal travelExpense;

    // Employment Information
    private LocalDate engagementStartDate;
}