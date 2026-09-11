package com.timesheet.validator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.master")
@Data
public class AppProperties {

    /** Single default SOW (backward-compatible) */
    private SowProps sow = new SowProps();

    /** Multiple SOW definitions — each resource can be mapped to one or more */
    private List<SowProps> sows = new ArrayList<>();

    private List<HolidayProps>  holidays  = new ArrayList<>();
    private Double defaultWorkingHoursPerDay = 8.0;
    private List<ResourceProps> resources = new ArrayList<>();
    private ValidationProps     validation = new ValidationProps();

    @Data
    public static class SowProps {
        private String     sowNumber;
        private String     poNumber;
        /** Additional PO numbers registered under this SOW (SOW_PO).
         *  The Commercial header carries the primary {@link #poNumber};
         *  per-resource Summary rows may reference any of these. */
        private List<String> poNumbers = new ArrayList<>();
        private BigDecimal poValue;
        private String     client;
        private String     description;
        private String     startDate;   // yyyy-MM-dd
        private String     endDate;     // yyyy-MM-dd
        private boolean    active = true;
        /** Resource IDs that are allowed to use this SOW */
        private List<String> resourceIds = new ArrayList<>();
    }

    @Data
    public static class HolidayProps {
        private String holidayDate;
        private String holidayName;
        private String countryCode;
        private String notes;
    }

    @Data
    public static class ResourceProps {
        private String     resourceId;
        private String     name;
        private BigDecimal dailyRateUsd;
        private Double     workingHoursPerDay;
        private String     startDate;
        private String     endDate;
    }

    @Data
    public static class ValidationProps {
        private double  maxHoursPerDay       = 8.0;
//        private double defaultWorkingHoursPerDay = 8.0;
        private boolean allowWeekendOverride = false;
        /** Default SOW used when no selection is made */
        private String  expectedSow          = "SOW_18_2026";
        private List<String> weekendOverrideReasons = new ArrayList<>(List.of(
            "Production incident / on-call support",
            "Client-requested emergency delivery",
            "Critical deadline — pre-approved by manager",
            "Go-live weekend support",
            "Other — see task description"
        ));
    }
}
