package com.timesheet.validator.domain;

import lombok.*;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SowPoId implements Serializable {

    private String sowNumber;

    private String poNumber;
}