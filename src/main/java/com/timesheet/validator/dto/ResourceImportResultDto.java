package com.timesheet.validator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceImportResultDto {

    private List<ResourceImportDto> resources;

    private List<ResourceImportMismatchDto> mismatches;

    private boolean requiresConfirmation;
}