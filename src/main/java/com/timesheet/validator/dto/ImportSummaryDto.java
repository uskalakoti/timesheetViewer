package com.timesheet.validator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportSummaryDto {

    private int resourcesImported;

    private int resourceMappingsImported;

    private int sowsImported;

    private int posImported;

    private String message;
}