package com.timesheet.validator.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class SowMasterViewDto {

    private Long id;

    // SOW details
    private String sowNumber;
    private String project;
    private String projectLocation;
    private String description;
    private LocalDate sowStartDate;
    private LocalDate sowEndDate;

    // PO details
    private String poNumber;
    private String updatedPoNumber;
    private LocalDate poStartDate;
    private LocalDate poEndDate;
    private BigDecimal poValue;

    // Status
    private Boolean active;

    public SowMasterViewDto(
            Long id,
            String sowNumber,
            String project,
            String projectLocation,
            String description,
            LocalDate sowStartDate,
            LocalDate sowEndDate,
            String poNumber,
            String updatedPoNumber,
            LocalDate poStartDate,
            LocalDate poEndDate,
            BigDecimal poValue,
            Boolean active) {

        this.id = id;
        this.sowNumber = sowNumber;
        this.project = project;
        this.projectLocation = projectLocation;
        this.description = description;
        this.sowStartDate = sowStartDate;
        this.sowEndDate = sowEndDate;
        this.poNumber = poNumber;
        this.updatedPoNumber = updatedPoNumber;
        this.poStartDate = poStartDate;
        this.poEndDate = poEndDate;
        this.poValue = poValue;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getSowNumber() {
        return sowNumber;
    }

    public String getProject() {
        return project;
    }

    public String getProjectLocation() {
        return projectLocation;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getSowStartDate() {
        return sowStartDate;
    }

    public LocalDate getSowEndDate() {
        return sowEndDate;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public String getUpdatedPoNumber() {
        return updatedPoNumber;
    }

    public LocalDate getPoStartDate() {
        return poStartDate;
    }

    public LocalDate getPoEndDate() {
        return poEndDate;
    }

    public BigDecimal getPoValue() {
        return poValue;
    }

    public Boolean getActive() {
        return active;
    }
}