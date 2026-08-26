package com.timesheet.validator.service;

import com.timesheet.validator.dto.SowImportDto;
import com.timesheet.validator.dto.SowImportValidationResultDto;

import java.util.List;

public class SowImportValidationException
        extends RuntimeException {

    private final List<SowImportDto> sows;

    private final SowImportValidationResultDto validationResult;


    public SowImportValidationException(
            List<SowImportDto> sows,
            SowImportValidationResultDto validationResult) {

        super(
                "SOW Master upload contains mapping discrepancies."
        );

        this.sows = sows;
        this.validationResult = validationResult;
    }


    public List<SowImportDto> getSows() {
        return sows;
    }


    public SowImportValidationResultDto getValidationResult() {
        return validationResult;
    }
}