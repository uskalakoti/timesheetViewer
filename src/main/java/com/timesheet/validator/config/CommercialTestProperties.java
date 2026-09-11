package com.timesheet.validator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Defect 7.5 — temporary hardcoded Commercial Sheet test data.
 *
 * <p>Until dynamic data mapping is implemented, selected Commercial Sheet
 * header fields (PO Number, PO Value, Total Billable Headcount) are
 * populated with predefined test values so the existing Commercial
 * validations and UI functionality can be exercised independently.</p>
 *
 * <p>Scope guards (per the change request):
 * <ul>
 *   <li>only blank cells are populated — uploaded values always win;</li>
 *   <li>no other Commercial Sheet calculation or validation is affected;</li>
 *   <li>intended for non-production/testing environments — disable via
 *       {@code app.commercial.test-data.enabled=false} (or clear the values)
 *       once integration with the source systems is completed.</li>
 * </ul></p>
 */
@Component
@ConfigurationProperties(prefix = "app.commercial.test-data")
@Data
public class CommercialTestProperties {

    /** Master switch for the hardcoded test data (testing environments only). */
    private boolean enabled = false;

    /** Value written into a blank "PO Number" header cell. */
    private String poNumber;

    /** Value written into a blank "PO Value" header cell. */
    private BigDecimal poValue;

    /** Value written into a blank "Total Billable Headcount" header cell. */
    private Double totalBillableHeadcount;
}
