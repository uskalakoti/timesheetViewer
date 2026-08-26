package com.timesheet.validator.domain;
import lombok.*; import javax.persistence.*; import java.math.BigDecimal; import java.time.LocalDate;
@Entity @Table(name="RESOURCE") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Resource {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="RESOURCE_ID",unique=true,nullable=false) private String resourceId;
    @Column(name="NAME",nullable=false) private String name;

    @Column(name = "LOCATION")
    private String location;

//    @Column(name = "COMPANY")
//    private String company;

    @Column(name = "COMPANY")
    @Builder.Default
    private String company = "Atain";


    @Column(name="DAILY_RATE_USD") private BigDecimal dailyRateUsd;
    @Column(name="START_DATE") private LocalDate startDate;
    @Column(name="END_DATE") private LocalDate endDate;

//    @Column(name = "WORKING_HOURS_PER_DAY")
//    private Double workingHoursPerDay;

    @Column(name = "WORKING_HOURS_PER_DAY")
    @Builder.Default
    private Double workingHoursPerDay = 8.0;
}
