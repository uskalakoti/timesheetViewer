package com.timesheet.validator.config;

import com.timesheet.validator.domain.*;
import com.timesheet.validator.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class MasterDataLoader implements ApplicationRunner {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AppProperties          props;
    private final PublicHolidayRepository holidayRepo;
    private final ResourceRepository      resourceRepo;
    private final RoleRepository          roleRepo;
    private final AppUserRepository       userRepo;
    private final SowMasterRepository     sowRepo;
    private final SowPoRepository         sowPoRepo;
    private final ResourceSowRepository   resourceSowRepo;
    private final PasswordEncoder         passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("=== [MasterDataLoader] Seeding from application.yml ===");
        seedHolidays();
        seedResources();
        seedSows();
        seedRolesAndUsers();
        log.info("=== Done: holidays={} resources={} sows={} users={} ===",
            holidayRepo.count(), resourceRepo.count(), sowRepo.count(), userRepo.count());
    }

    private LocalDate parse(String s) {
        return (s == null || s.isBlank()) ? null : LocalDate.parse(s.trim(), FMT);
    }

    private void seedHolidays() {
        holidayRepo.deleteAllInBatch();
        holidayRepo.saveAll(props.getHolidays().stream()
            .map(h -> PublicHoliday.builder()
                .holidayDate(parse(h.getHolidayDate()))
                .holidayName(h.getHolidayName())
                .countryCode(h.getCountryCode())
                .enabled(true)
                .notes(h.getNotes()).build())
            .collect(Collectors.toList()));
        log.info("[Loader] Holidays: {}", holidayRepo.count());
    }

    private void seedResources() {
        resourceRepo.deleteAllInBatch();

        resourceRepo.saveAll(
                props.getResources()
                        .stream()
                        .map(r -> Resource.builder()
                                .resourceId(r.getResourceId())
                                .name(r.getName())
                                .dailyRateUsd(r.getDailyRateUsd())
                                .startDate(parse(r.getStartDate()))
                                .endDate(parse(r.getEndDate()))
                                .workingHoursPerDay(
                                        r.getWorkingHoursPerDay() != null
                                            ? r.getWorkingHoursPerDay()
                                            : props.getDefaultWorkingHoursPerDay()
                                )
                                .build())
                        .collect(Collectors.toList())
        );

        log.info("[Loader] Resources: {}", resourceRepo.count());
    }

    private void seedSows() {
        // Wipe existing SOW master and mappings
        resourceSowRepo.deleteAllInBatch();
        sowRepo.deleteAllInBatch();

        List<AppProperties.SowProps> sowList = props.getSows();
        if (sowList == null || sowList.isEmpty()) {
            // Fall back to the single sow: block for backward compatibility
            AppProperties.SowProps s = props.getSow();
            if (s != null && s.getSowNumber() != null) {
                sowList = List.of(s);
            } else {
                log.warn("[Loader] No SOWs configured in YAML");
                return;
            }
        }

        for (AppProperties.SowProps sp : sowList) {
            SowMaster sow = SowMaster.builder()
                .sowNumber(sp.getSowNumber())
                .poNumber(sp.getPoNumber())
                .poValue(sp.getPoValue())
                .client(sp.getClient())
                .description(sp.getDescription())
                .startDate(parse(sp.getStartDate()))
                .endDate(parse(sp.getEndDate()))
                .active(sp.isActive())
                .build();
            sowRepo.save(sow);

            // Seed additional POs for this SOW (SOW_PO supports the sheet
            // layout where each resource row carries its own PO number and
            // the Commercial header carries the primary one).
            if (sp.getPoNumbers() != null) {
                for (String extraPo : sp.getPoNumbers()) {
                    if (extraPo == null || extraPo.isBlank()) continue;
                    sowPoRepo.save(SowPo.builder()
                        .sowNumber(sp.getSowNumber())
                        .poNumber(extraPo.trim())
                        .build());
                }
            }

            // Seed resource-SOW mappings
            if (sp.getResourceIds() != null) {
                for (String rid : sp.getResourceIds()) {
                    resourceSowRepo.save(ResourceSow.builder()
                        .resourceId(rid)
                        .sowNumber(sp.getSowNumber())
                        .roleInSow("Developer")
                        .build());
                }
            }
        }
        log.info("[Loader] SOWs: {}, ResourceSow mappings: {}",
            sowRepo.count(), resourceSowRepo.count());
    }

    private void seedRolesAndUsers() {
        Role adminRole   = roleRepo.findByName("ADMIN").orElseGet(() ->
            roleRepo.save(Role.builder().name("ADMIN")
                .description("Full access — upload, validate, manage").build()));
        Role managerRole = roleRepo.findByName("MANAGER").orElseGet(() ->
            roleRepo.save(Role.builder().name("MANAGER")
                .description("Upload, validate, view reports").build()));
        Role userRole    = roleRepo.findByName("USER").orElseGet(() ->
            roleRepo.save(Role.builder().name("USER")
                .description("Submit own timesheet entries").build()));

        seedUser("admin",   "Admin123!",   "System Administrator", "admin@company.com",   adminRole,   null);
        seedUser("manager", "Manager123!", "Project Manager",      "manager@company.com", managerRole, null);
        for (Resource r : resourceRepo.findAll()) {
            String uname = r.getName().toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .substring(0, Math.min(r.getName().replaceAll("[^a-zA-Z0-9]","").length(), 12));
            seedUser(uname, "User123!", r.getName(), uname + "@company.com", userRole, r.getResourceId());
        }
        log.info("[Loader] Roles: {}, Users: {}", roleRepo.count(), userRepo.count());
    }

    private void seedUser(String username, String rawPwd, String fullName,
                          String email, Role role, String resourceId) {
        if (!userRepo.existsByUsername(username)) {
            userRepo.save(AppUser.builder()
                .username(username).password(passwordEncoder.encode(rawPwd))
                .fullName(fullName).email(email).enabled(true)
                .resourceId(resourceId)
                .roles(new HashSet<>(Set.of(role))).build());
        }
    }
}
