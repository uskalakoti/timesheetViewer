package com.timesheet.validator.domain;

import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "SOW_MASTER")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SowMaster {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "SOW_NUMBER", unique = true, nullable = false)
    private String sowNumber;

    @Column(name = "PROJECT")
    private String project;

    @Column(name = "PROJECT_LOCATION")
    private String projectLocation;

    @Column(name = "PO_NUMBER")   private String     poNumber;
    @Column(name = "PO_VALUE")    private BigDecimal poValue;
    @Column(name = "CLIENT")      private String     client;
    @Column(name = "DESCRIPTION", length = 500)
                                  private String     description;
    @Column(name = "START_DATE")  private LocalDate  startDate;
    @Column(name = "END_DATE")    private LocalDate  endDate;
    @Column(name = "ACTIVE")      private Boolean    active;
}
