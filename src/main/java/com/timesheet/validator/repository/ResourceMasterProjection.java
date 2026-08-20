package com.timesheet.validator.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ResourceMasterProjection {

    String getEmployeeId();

    String getEmployeeName();

    String getEmployeeLocation();

    String getCompany();

    BigDecimal getDailyRateUsd();

    Double getWorkingHoursPerDay();

    LocalDate getStartDate();

    LocalDate getEndDate();

    String getAssignedTeam();

    String getSubProject();

    String getProjectCode();

    BigDecimal getTravelExpense();

    String getSowNumber();

    String getRoleInSow();

    String getProject();

    String getProjectLocation();

    String getSowDescription();

    String getPoNumber();

    String getUpdatedPoNumber();

    BigDecimal getPoValue();

    LocalDate getPoStartDate();

    LocalDate getPoEndDate();
}