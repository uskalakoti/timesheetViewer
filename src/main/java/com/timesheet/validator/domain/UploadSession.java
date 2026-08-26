package com.timesheet.validator.domain;
import lombok.*; import javax.persistence.*; import java.time.LocalDateTime;
@Entity @Table(name="UPLOAD_SESSION") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UploadSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="SESSION_ID",unique=true,nullable=false) private String sessionId;
    @Column(name="FILE_NAME") private String fileName;
    @Column(name="UPLOADED_AT") private LocalDateTime uploadedAt;
    @Column(name="SHEET_COUNT") private Integer sheetCount;
    @Column(name="STATUS") private String status;
    @Column(name="ENABLED_RULES", length = 1000)
    private String enabledRules;
    /** Phased validation: TIMESHEET (default) then PIVOT once Timesheet is clean. */
    @Column(name="VALIDATION_PHASE")
    private String validationPhase;
    /** Project/template selected before upload: SYDNEY_SOFTDEV or GENERALIZED_TIMESHEET. */
    @Column(name="UPLOAD_PROJECT", length = 50)
    private String uploadProject;
    @PrePersist public void pre() {
        if(uploadedAt==null) uploadedAt=LocalDateTime.now();
        if(validationPhase==null) validationPhase="TIMESHEET";
    }
}
