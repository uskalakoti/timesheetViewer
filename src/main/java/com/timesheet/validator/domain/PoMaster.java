package com.timesheet.validator.domain;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "PO_MASTER")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "PO_NUMBER", nullable = false, unique = true)
    private String poNumber;

    @Column(name = "UPDATED_PO_NUMBER")
    private String updatedPoNumber;

    @Column(name = "PO_VALUE")
    private BigDecimal poValue;

    @Column(name = "START_DATE")
    private LocalDate startDate;

    @Column(name = "END_DATE")
    private LocalDate endDate;

    @Column(name = "ACTIVE")
    private Boolean active;
}