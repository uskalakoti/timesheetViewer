package com.timesheet.validator.domain;

import lombok.*;

import javax.persistence.*;

@Entity
@Table(name = "SOW_PO")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(SowPoId.class)
public class SowPo {

    @Id
    @Column(name = "SOW_NUMBER")
    private String sowNumber;

    @Id
    @Column(name = "PO_NUMBER")
    private String poNumber;
}