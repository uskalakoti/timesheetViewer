package com.timesheet.validator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@AllArgsConstructor
public class ResourceMasterViewDto {

    private Long id;

    /*
     * ------------------------------------------------------------
     * RESOURCE
     * ------------------------------------------------------------
     */

    private String employeeId;

    private String employeeName;

    private String location;

    private String company;

    private BigDecimal dailyRateUsd;

    private Double workingHoursPerDay;

    private LocalDate startDate;

    private LocalDate endDate;


    /*
     * ------------------------------------------------------------
     * RESOURCE_SOW
     * ------------------------------------------------------------
     */

    private String assignedTeam;

    private String project;

    private String subProject;

    private String projectCode;

    private String sowNumber;

    private String roleInSow;

    private BigDecimal travelExpense;


    /*
     * ------------------------------------------------------------
     * SOW_MASTER
     * ------------------------------------------------------------
     */

    private String sowDescription;

    private LocalDate sowStartDate;

    private LocalDate sowEndDate;


    /*
     * ------------------------------------------------------------
     * PO_MASTER
     * ------------------------------------------------------------
     */

    private String poNumber;

    private String updatedPoNumber;

    private BigDecimal poValue;

    private LocalDate poStartDate;

    private LocalDate poEndDate;
}