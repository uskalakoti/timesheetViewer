package com.timesheet.validator.domain;

import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "RESOURCE_SOW")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@IdClass(ResourceSowId.class)
public class ResourceSow {

    @Id @Column(name = "RESOURCE_ID")
    private String resourceId;

    @Id @Column(name = "SOW_NUMBER")
    private String sowNumber;

    @Column(name = "ROLE_IN_SOW")
    private String roleInSow;

    @Column(name = "ASSIGNED_TEAM")
    private String assignedTeam;

    @Column(name = "PROJECT_CODE")
    private String projectCode;

    @Column(name = "SUB_PROJECT")
    private String subProject;

    @Column(name = "TRAVEL_EXPENSE")
    private BigDecimal travelExpense;
}
