package com.timesheet.validator;

import com.timesheet.validator.config.AppProperties;
import com.timesheet.validator.domain.TimesheetEntry;
import com.timesheet.validator.dto.TimesheetEntryForm;
import com.timesheet.validator.repository.*;
import com.timesheet.validator.service.TimesheetEntryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests — starts the full Spring context including:
 *   Liquibase (creates all tables from 001-schema.xml)
 *   MasterDataLoader (seeds holidays, resources, roles, users)
 *   Spring Security (via @WithMockUser for secured method tests)
 */
@SpringBootTest
@ActiveProfiles("test")
class ExcelViewerApplicationTests {

    @Autowired PublicHolidayRepository  holidayRepo;
    @Autowired ResourceRepository       resourceRepo;
    @Autowired AppUserRepository        userRepo;
    @Autowired RoleRepository           roleRepo;
    @Autowired TimesheetEntryRepository entryRepo;
    @Autowired TimesheetEntryService    entryService;
    @Autowired AppProperties            props;

    // ── Context and master data ──────────────────────────────────────────────

    @Test
    @DisplayName("Spring context loads and all repositories are available")
    void contextLoads() {
        assertThat(holidayRepo).isNotNull();
        assertThat(resourceRepo).isNotNull();
        assertThat(userRepo).isNotNull();
        assertThat(roleRepo).isNotNull();
        assertThat(entryRepo).isNotNull();
    }

    @Test
    @DisplayName("Holidays seeded from application.yml on startup")
    void holidaysSeeded() {
        assertThat(holidayRepo.count()).isGreaterThanOrEqualTo(1);
        assertThat(holidayRepo.findAll())
                .anyMatch(h -> "Holi".equalsIgnoreCase(h.getHolidayName()));
    }

    @Test
    @DisplayName("All 6 resources seeded from application.yml")
    void resourcesSeeded() {
        assertThat(resourceRepo.count()).isEqualTo(6);
        // Use names as defined in application.yml (SOW_19_2026 roster)
        assertThat(resourceRepo.findByName("Deepa Malik")).isPresent();
        assertThat(resourceRepo.findByName("Gaurav Kumar")).isPresent();
        assertThat(resourceRepo.findByName("Monthly on call Support")).isPresent();
    }

    @Test
    @DisplayName("Security roles seeded: ADMIN, MANAGER, USER")
    void rolesSeeded() {
        assertThat(roleRepo.count()).isGreaterThanOrEqualTo(3);
        assertThat(roleRepo.findByName("ADMIN")).isPresent();
        assertThat(roleRepo.findByName("MANAGER")).isPresent();
        assertThat(roleRepo.findByName("USER")).isPresent();
    }

    @Test
    @DisplayName("Default admin and manager users seeded")
    void usersSeeded() {
        assertThat(userRepo.findByUsername("admin")).isPresent();
        assertThat(userRepo.findByUsername("manager")).isPresent();
        // Admin must have ADMIN role
        userRepo.findByUsername("admin").ifPresent(u ->
            assertThat(u.getRoles()).anyMatch(r -> "ADMIN".equals(r.getName()))
        );
    }

    @Test
    @DisplayName("AppProperties validation config loads with correct defaults")
    void validationConfigLoads() {
        AppProperties.ValidationProps v = props.getValidation();
        assertThat(v.getMaxHoursPerDay()).isEqualTo(8.0);
        assertThat(v.getExpectedSow()).isEqualTo("SOW_19_2026");
        assertThat(v.getWeekendOverrideReasons()).isNotEmpty();
    }

    // ── Timesheet entry validation rules ─────────────────────────────────────

    @Test
    @DisplayName("TS-02: Weekend entries blocked without override")
    void ts02WeekendBlocked() {
        TimesheetEntryForm form = weekdayForm();
        // Find next Saturday
        LocalDate saturday = LocalDate.now();
        while (saturday.getDayOfWeek() != java.time.DayOfWeek.SATURDAY) {
            saturday = saturday.plusDays(1);
        }
        form.setEntryDate(saturday.toString());
        form.setWeekendOverride(false);

        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).anyMatch(e -> e.startsWith("TS-02"));
    }

    @Test
    @DisplayName("TS-02: Weekend entry allowed when override checkbox + reason provided")
    void ts02WeekendOverrideAllowed() {
        TimesheetEntryForm form = weekdayForm();
        LocalDate saturday = LocalDate.now();
        while (saturday.getDayOfWeek() != java.time.DayOfWeek.SATURDAY) {
            saturday = saturday.plusDays(1);
        }
        form.setEntryDate(saturday.toString());
        form.setWeekendOverride(true);
        form.setWeekendOverrideReason("Production incident / on-call support");

        // Should not have TS-02 error
        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).noneMatch(e -> e.startsWith("TS-02"));
    }

    @Test
    @DisplayName("TS-02: Weekend override requires a reason — error when reason blank")
    void ts02WeekendOverrideMissingReason() {
        TimesheetEntryForm form = weekdayForm();
        LocalDate sunday = LocalDate.now();
        while (sunday.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
            sunday = sunday.plusDays(1);
        }
        form.setEntryDate(sunday.toString());
        form.setWeekendOverride(true);
        form.setWeekendOverrideReason("");   // blank reason

        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).anyMatch(e -> e.contains("mandatory reason"));
    }

    @Test
    @DisplayName("TS-03: Public holiday entries are always blocked (override does not apply)")
    void ts03HolidayBlocked() {
        // Use a known seeded holiday — Republic Day
        TimesheetEntryForm form = weekdayForm();
        form.setEntryDate("2026-01-26");

        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).anyMatch(e -> e.startsWith("TS-03"));
    }

    @Test
    @DisplayName("TS-04: Zero hours is rejected")
    void ts04ZeroHoursRejected() {
        TimesheetEntryForm form = weekdayForm();
        form.setHours(BigDecimal.ZERO);

        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).anyMatch(e -> e.startsWith("TS-04"));
    }

    @Test
    @DisplayName("TS-06: SOW mismatch is flagged as an error")
    void ts06SowMismatch() {
        TimesheetEntryForm form = weekdayForm();
        form.setSow("WRONG_SOW_NUMBER");

        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).anyMatch(e -> e.startsWith("TS-06"));
    }

    @Test
    @DisplayName("TS-06: Correct SOW passes without error")
    void ts06SowMatches() {
        TimesheetEntryForm form = weekdayForm(); // uses expectedSow from AppProperties

        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).noneMatch(e -> e.startsWith("TS-06"));
    }

    @Test
    @DisplayName("Valid weekday entry with correct data passes all rules")
    void validEntryPassesAllRules() {
        TimesheetEntryForm form = weekdayForm();
        List<String> errors = entryService.validate(form, "AKK");
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("Weekend override entry saves with WEEKEND-OVERRIDE status and reason stored")
    void weekendOverrideSavesCorrectly() {
        TimesheetEntryForm form = weekdayForm();
        LocalDate saturday = LocalDate.now();
        while (saturday.getDayOfWeek() != java.time.DayOfWeek.SATURDAY) {
            saturday = saturday.plusDays(1);
        }
        form.setEntryDate(saturday.toString());
        form.setWeekendOverride(true);
        form.setWeekendOverrideReason("Go-live weekend support");

        TimesheetEntry saved = entryService.save(form, "testuser", "AKK");
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo("WEEKEND-OVERRIDE");
        assertThat(saved.getWeekendOverride()).isTrue();
        assertThat(saved.getWeekendOverrideReason()).isEqualTo("Go-live weekend support");
        assertThat(saved.getTask()).contains("[WEEKEND OVERRIDE: Go-live weekend support]");

        // Clean up
        entryRepo.delete(saved);
    }

    @Test
    @DisplayName("entryDate String parsed correctly to LocalDate by service")
    void dateStringParsedCorrectly() {
        assertThat(entryService.parseDate("2026-03-15")).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(entryService.parseDate("")).isNull();
        assertThat(entryService.parseDate(null)).isNull();
        assertThat(entryService.parseDate("not-a-date")).isNull();
    }

    @Test
    @DisplayName("TIMESHEET_ENTRY table has WEEKEND_OVERRIDE column (schema validation)")
    void timesheetEntryHasOverrideColumns() {
        // If the schema is correct, this save/load cycle works without exception
        TimesheetEntry e = TimesheetEntry.builder()
                .resourceName("AKK")
                .entryDate(nextWeekday())
                .hours(new BigDecimal("4.0"))
                .sow(props.getValidation().getExpectedSow())
                .status("SUBMITTED")
                .weekendOverride(false)
                .weekendOverrideReason(null)
                .build();
        TimesheetEntry saved = entryRepo.save(e);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getWeekendOverride()).isFalse();
        entryRepo.delete(saved);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Returns a form pre-filled with valid weekday data. */
    private TimesheetEntryForm weekdayForm() {
        TimesheetEntryForm form = new TimesheetEntryForm();
        form.setEntryDate(nextWeekday().toString());          // always a Mon-Fri
        form.setHours(new BigDecimal("8.0"));
        form.setProject("Australia Maintenance");
        form.setSubProject("UCP");
        form.setProjectCode("00IW2");
        form.setAssignedTeam("R");
        form.setTask("Development work");
        form.setSow(props.getValidation().getExpectedSow()); // correct SOW
        form.setCompany(props.getSow().getClient());
        form.setCountryCode("IN");
        form.setWeekendOverride(false);
        return form;
    }

    /** Returns the next Monday–Friday date from today. */
    private LocalDate nextWeekday() {
        LocalDate d = LocalDate.now().plusDays(7); // go a week ahead to avoid today's holiday risk
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    @Test
    @DisplayName("TS-01: Multiple tasks on same day — cumulative hours cap enforced")
    void ts01CumulativeHoursCapEnforced() {
        LocalDate day = nextWeekday();
        String resource = "AKK";

        // Save first task: 5 hrs
        TimesheetEntryForm form1 = weekdayForm();
        form1.setEntryDate(day.toString());
        form1.setHours(new BigDecimal("5.0"));
        TimesheetEntry e1 = entryService.save(form1, "testuser", resource);

        // Second task: 4 hrs — total would be 9, exceeds max 8
        TimesheetEntryForm form2 = weekdayForm();
        form2.setEntryDate(day.toString());
        form2.setHours(new BigDecimal("4.0"));
        List<String> errors = entryService.validate(form2, resource);
        assertThat(errors).anyMatch(e -> e.startsWith("TS-01"));

        // Third task: 3 hrs — total would be 8, exactly at cap — should pass
        TimesheetEntryForm form3 = weekdayForm();
        form3.setEntryDate(day.toString());
        form3.setHours(new BigDecimal("3.0"));
        List<String> ok = entryService.validate(form3, resource);
        assertThat(ok).noneMatch(e -> e.startsWith("TS-01"));

        // Clean up
        entryRepo.delete(e1);
    }

    @Test
    @DisplayName("getTasksForDay returns only tasks for specific resource and date")
    void getTasksForDayFiltersCorrectly() {
        LocalDate day = nextWeekday();
        String resource = "AKK";
        // Save two tasks
        TimesheetEntryForm f1 = weekdayForm(); f1.setEntryDate(day.toString()); f1.setHours(new BigDecimal("2"));
        TimesheetEntryForm f2 = weekdayForm(); f2.setEntryDate(day.toString()); f2.setHours(new BigDecimal("3"));
        TimesheetEntry t1 = entryService.save(f1, "testuser", resource);
        TimesheetEntry t2 = entryService.save(f2, "testuser", resource);

        List<TimesheetEntry> tasks = entryService.getTasksForDay(resource, day);
        assertThat(tasks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(tasks).allMatch(t -> t.getResourceName().equals(resource));

        // Clean up
        entryRepo.delete(t1); entryRepo.delete(t2);
    }

    @Test
    @DisplayName("getLoggedHours sums correctly across multiple tasks")
    void getLoggedHoursAggregatesCorrectly() {
        LocalDate day = nextWeekday();
        String resource = "AKK";
        TimesheetEntryForm f1 = weekdayForm(); f1.setEntryDate(day.toString()); f1.setHours(new BigDecimal("2.5"));
        TimesheetEntryForm f2 = weekdayForm(); f2.setEntryDate(day.toString()); f2.setHours(new BigDecimal("3.5"));
        TimesheetEntry t1 = entryService.save(f1, "testuser", resource);
        TimesheetEntry t2 = entryService.save(f2, "testuser", resource);

        BigDecimal total = entryService.getLoggedHours(resource, day);
        assertThat(total).isGreaterThanOrEqualTo(new BigDecimal("6.0"));

        entryRepo.delete(t1); entryRepo.delete(t2);
    }

    @Test
    @DisplayName("deleteEntry removes the entry from the database")
    void deleteEntryRemovesRecord() {
        TimesheetEntryForm form = weekdayForm();
        TimesheetEntry saved = entryService.save(form, "testuser", "AKK");
        Long id = saved.getId();
        assertThat(entryRepo.findById(id)).isPresent();

        entryService.deleteEntry(id);
        assertThat(entryRepo.findById(id)).isEmpty();
    }

}