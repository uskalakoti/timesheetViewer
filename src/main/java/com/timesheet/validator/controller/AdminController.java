package com.timesheet.validator.controller;

import java.util.Optional;

import com.timesheet.validator.domain.PoMaster;
import com.timesheet.validator.domain.SowPo;
import com.timesheet.validator.domain.SowPoId;

import com.timesheet.validator.repository.PoMasterRepository;
import com.timesheet.validator.repository.SowPoRepository;

import org.springframework.transaction.annotation.Transactional;

import com.timesheet.validator.dto.ResourceFormDto;
import com.timesheet.validator.dto.SowMasterEditDto;
import com.timesheet.validator.domain.ResourceSow;
import com.timesheet.validator.domain.ResourceSowId;
import com.timesheet.validator.domain.SowMaster;
import com.timesheet.validator.repository.ResourceSowRepository;
import com.timesheet.validator.repository.SowMasterRepository;
import org.springframework.transaction.annotation.Transactional;


import com.timesheet.validator.repository.ResourceRepository;
import com.timesheet.validator.repository.ResourceMasterRepository;
import com.timesheet.validator.dto.ResourceMasterViewDto;

import com.timesheet.validator.service.ExcelParserService;
import com.timesheet.validator.service.LeavePlannerWorkbookService;
import com.timesheet.validator.service.SheetViewService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
//import com.timesheet.validator.domain.Resource;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;

import com.timesheet.validator.service.LeavePlannerValidationService;
import com.timesheet.validator.service.LeavePlannerImportService;
import java.util.List;


import com.timesheet.validator.config.AppProperties;
import com.timesheet.validator.domain.*;
import com.timesheet.validator.config.RuleCatalog;
import com.timesheet.validator.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;



@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final ResourceRepository     resourceRepo;
    private final ResourceSowRepository resourceSowRepo;
    private final SowMasterRepository    sowMasterRepo;
    private final PoMasterRepository poMasterRepo;
    private final SowPoRepository sowPoRepo;
    private final ResourceMasterRepository resourceMasterRepository;
    private final SowMasterRepository sowMasterRepository;
    private final SowPoRepository sowPoRepository;
    private final PoMasterRepository poMasterRepository;
    private final PublicHolidayRepository holidayRepo;
    private final AppUserRepository      userRepo;
    private final RoleRepository         roleRepo;
    private final PasswordEncoder        passwordEncoder;
    private final RuleCatalog            ruleCatalog;
    private final AppProperties props;

    private final LeavePlannerValidationService leavePlannerValidationService;
    private final LeavePlannerImportService     leavePlannerImportService;
//    private final LeavePlannerWorkbookService leavePlannerWorkbookService;


    private final ExcelParserService excelParserService;

    private final SheetViewService sheetViewService;

    private final UploadSessionRepository uploadSessionRepository;


    // ══════════════════════════════════════════════════════════════════════════
    // VALIDATION RULES (enable / disable from DB)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/rules")
    public String rules(Model model) {
        model.addAttribute("ruleGroups", ruleCatalog.getAllRulesBySheet());
        return "pages/admin/rules";
    }

    @PostMapping("/rules/toggle/{ruleId}")
    public String toggleRule(@PathVariable String ruleId,
                             @RequestParam(defaultValue = "true") boolean enabled,
                             RedirectAttributes ra) {
        ruleCatalog.setEnabled(ruleId, enabled);
        ra.addFlashAttribute("success",
                "Rule '" + ruleId + "' " + (enabled ? "enabled" : "disabled") + ".");
        return "redirect:/admin/rules";
    }

    @GetMapping("/rules/new")
    public String newRule(Model model) {
        RuleConfig rc = new RuleConfig();
        rc.setEnabled(true);
        rc.setAlwaysOn(false);
        rc.setSeverity("CRITICAL");
        rc.setSortOrder(0);
        model.addAttribute("rule", rc);
        model.addAttribute("editMode", false);
        return "pages/admin/rule-form";
    }

    @GetMapping("/rules/edit/{id}")
    public String editRule(@PathVariable Long id, Model model) {
        RuleConfig rc = ruleCatalog.getRule(id)
                .orElseThrow(() -> new RuntimeException("Rule not found: " + id));
        model.addAttribute("rule", rc);
        model.addAttribute("editMode", true);
        return "pages/admin/rule-form";
    }

    @PostMapping("/rules/save")
    public String saveRule(
            @RequestParam(required = false) Long id,
            @RequestParam String ruleId,
            @RequestParam(required = false) String sheetName,
            @RequestParam(required = false) String description,
            @RequestParam(required = false, defaultValue = "CRITICAL") String severity,
            @RequestParam(required = false, defaultValue = "false") boolean alwaysOn,
            @RequestParam(required = false, defaultValue = "false") boolean enabled,
            @RequestParam(required = false, defaultValue = "0") int sortOrder,
            @RequestParam(required = false) String messageTemplate,
            RedirectAttributes ra) {

        String rid = ruleId == null ? "" : ruleId.trim();
        if (rid.isEmpty()) {
            ra.addFlashAttribute("error", "Rule ID is required.");
            return "redirect:/admin/rules/new";
        }
        // Guard against duplicate rule IDs on create
        if (id == null && ruleCatalog.ruleIdExists(rid)) {
            ra.addFlashAttribute("error", "Rule ID '" + rid + "' already exists.");
            return "redirect:/admin/rules/new";
        }

        RuleConfig rc = id != null
                ? ruleCatalog.getRule(id).orElseGet(RuleConfig::new)
                : new RuleConfig();
        rc.setRuleId(rid);
        rc.setSheetName(sheetName);
        rc.setDescription(description);
        rc.setSeverity(severity);
        rc.setAlwaysOn(alwaysOn);
        rc.setEnabled(enabled);
        rc.setSortOrder(sortOrder);
        rc.setMessageTemplate(messageTemplate != null && messageTemplate.isBlank() ? null : messageTemplate);
        ruleCatalog.saveRule(rc);

        ra.addFlashAttribute("success", "Rule '" + rc.getRuleId() + "' saved.");
        return "redirect:/admin/rules";
    }

    @PostMapping("/rules/delete/{id}")
    public String deleteRule(@PathVariable Long id, RedirectAttributes ra) {
        ruleCatalog.deleteRule(id);
        ra.addFlashAttribute("success", "Rule deleted.");
        return "redirect:/admin/rules";
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────
    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("resourceCount", resourceRepo.count());
        model.addAttribute("holidayCount",  holidayRepo.count());
        model.addAttribute("userCount",     userRepo.count());
        model.addAttribute("roleCount",     roleRepo.count());
        return "pages/admin/dashboard";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RESOURCES
    // ══════════════════════════════════════════════════════════════════════════

//    @GetMapping("/resources")
//    public String resources(Model model) {
//        model.addAttribute("resources", resourceRepo.findAll());
//        return "pages/admin/resources";
//    }

    @GetMapping("/resources")
    public String resources(Model model) {

        List<ResourceMasterViewDto> resources =
                resourceMasterRepository.findResourceMasterView();

        model.addAttribute("resources", resources);

        model.addAttribute(
                "sowMasters",
                sowMasterRepository.findSowMasterView()
        );

        return "pages/admin/resources";
    }

//    @GetMapping("/resources/new")
//    public String newResource(Model model) {
//        model.addAttribute("resource", new com.timesheet.validator.domain.Resource());
//        model.addAttribute("editMode", false);
//        return "pages/admin/resource-form";
//    }

    @GetMapping("/resources/new")
    public String newResource(Model model) {

        ResourceFormDto form = new ResourceFormDto();

        form.setCompany("Atain");
        form.setWorkingHoursPerDay(
                props.getDefaultWorkingHoursPerDay()
        );

        model.addAttribute("resource", form);
        model.addAttribute("editMode", false);

        return "pages/admin/resource-form";
    }

//    @GetMapping("/resources/edit/{id}")
//    public String editResource(@PathVariable Long id, Model model) {
//        com.timesheet.validator.domain.Resource r = resourceRepo.findById(id)
//                .orElseThrow(() -> new RuntimeException("Resource not found: " + id));
//        model.addAttribute("resource", r);
//        model.addAttribute("editMode", true);
//        return "pages/admin/resource-form";
//    }

    @GetMapping("/resources/edit/{id}")
    public String editResource(@PathVariable Long id, Model model) {

        com.timesheet.validator.domain.Resource r = resourceRepo.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Resource not found: " + id));

        ResourceFormDto form = new ResourceFormDto();

        // ============================================================
        // RESOURCE
        // ============================================================

        form.setId(r.getId());
        form.setResourceId(r.getResourceId());
        form.setName(r.getName());
        form.setLocation(r.getLocation());
        form.setCompany(r.getCompany());
        form.setDailyRateUsd(r.getDailyRateUsd());
        form.setWorkingHoursPerDay(r.getWorkingHoursPerDay());
        form.setStartDate(r.getStartDate());
        form.setEndDate(r.getEndDate());


        // ============================================================
        // RESOURCE_SOW
        // ============================================================

        ResourceSow resourceSow = resourceSowRepo
                .findByResourceId(r.getResourceId())
                .stream()
                .findFirst()
                .orElse(null);

        if (resourceSow != null) {

            form.setSowNumber(resourceSow.getSowNumber());
            form.setRoleInSow(resourceSow.getRoleInSow());
            form.setAssignedTeam(resourceSow.getAssignedTeam());
            form.setSubProject(resourceSow.getSubProject());
            form.setProjectCode(resourceSow.getProjectCode());
            form.setTravelExpense(resourceSow.getTravelExpense());

            // ========================================================
            // SOW_MASTER
            // ========================================================

            if (resourceSow.getSowNumber() != null) {

                sowMasterRepo
                        .findBySowNumber(resourceSow.getSowNumber())
                        .ifPresent(sow -> {

                            form.setProject(sow.getProject());
                            form.setProjectLocation(
                                    sow.getProjectLocation()
                            );

                            form.setSowDescription(
                                    sow.getDescription()
                            );

                            form.setSowStartDate(
                                    sow.getStartDate()
                            );

                            form.setSowEndDate(
                                    sow.getEndDate()
                            );
                        });

                // ====================================================
                // SOW_PO -> PO_MASTER
                // ====================================================

                List<SowPo> sowPos =
                        sowPoRepo.findBySowNumber(
                                resourceSow.getSowNumber()
                        );

                if (!sowPos.isEmpty()) {

                    // Current form supports one PO.
                    SowPo sowPo = sowPos.get(0);

                    form.setPoNumber(
                            sowPo.getPoNumber()
                    );


                    if (sowPo.getPoNumber() != null) {

                        poMasterRepo
                                .findByPoNumber(
                                        sowPo.getPoNumber()
                                )
                                .ifPresent(po -> {

                                    form.setUpdatedPoNumber(
                                            po.getUpdatedPoNumber()
                                    );

                                    form.setPoValue(
                                            po.getPoValue()
                                    );

                                    form.setPoStartDate(
                                            po.getStartDate()
                                    );

                                    form.setPoEndDate(
                                            po.getEndDate()
                                    );
                                });
                    }
                }

            }


        }


        model.addAttribute("resource", form);
        model.addAttribute("editMode", true);

        return "pages/admin/resource-form";
    }

//    @PostMapping("/resources/save")
//    public String saveResource(
//            @RequestParam(required = false) Long id,
//            @RequestParam String resourceId,
//            @RequestParam String name,
//            @RequestParam(required = false) String dailyRateUsd,
//            @RequestParam(required = false) Double workingHoursPerDay,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
//            RedirectAttributes ra) {
//        com.timesheet.validator.domain.Resource r = id != null ? resourceRepo.findById(id).orElse(new com.timesheet.validator.domain.Resource()) : new com.timesheet.validator.domain.Resource();
//        r.setResourceId(resourceId.trim());
//        r.setName(name.trim());
//        r.setWorkingHoursPerDay(
//                workingHoursPerDay != null
//                        ? workingHoursPerDay
//                        : props.getDefaultWorkingHoursPerDay()
//        );
//        if (dailyRateUsd != null && !dailyRateUsd.isBlank()) {
//            try { r.setDailyRateUsd(new BigDecimal(dailyRateUsd.trim())); }
//            catch (NumberFormatException ignored) {}
//        }
//        r.setStartDate(startDate);
//        r.setEndDate(endDate);
//        resourceRepo.save(r);
//        ra.addFlashAttribute("success", "Resource '" + r.getName() + "' saved.");
//        return "redirect:/admin/resources";
//    }


    @PostMapping("/resources/save")
    @Transactional
    public String saveResource(

            @RequestParam(required = false) Long id,

            @RequestParam String resourceId,
            @RequestParam String name,

            @RequestParam(required = false) String location,
            @RequestParam(required = false) String company,

            @RequestParam(required = false) String dailyRateUsd,

            @RequestParam(required = false)
            Double workingHoursPerDay,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,


            // RESOURCE_SOW
            @RequestParam(required = false) String assignedTeam,
            @RequestParam(required = false) String subProject,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String travelExpense,
            @RequestParam(required = false) String sowNumber,
            @RequestParam(required = false) String roleInSow,


            // SOW_MASTER
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String projectLocation,
            @RequestParam(required = false) String sowDescription,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate sowStartDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate sowEndDate,


            // PO_MASTER
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String updatedPoNumber,
            @RequestParam(required = false) String poValue,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate poStartDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate poEndDate,

            RedirectAttributes ra) {


        // ============================================================
        // 1. RESOURCE
        // ============================================================

        com.timesheet.validator.domain.Resource r = id != null
                ? resourceRepo.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Resource not found: " + id))
                : new com.timesheet.validator.domain.Resource();


        r.setResourceId(resourceId.trim());
        r.setName(name.trim());

        r.setLocation(
                location != null && !location.isBlank()
                        ? location.trim()
                        : null
        );

        r.setCompany(
                company != null && !company.isBlank()
                        ? company.trim()
                        : "Atain"
        );

        r.setWorkingHoursPerDay(
                workingHoursPerDay != null
                        ? workingHoursPerDay
                        : props.getDefaultWorkingHoursPerDay()
        );

        r.setStartDate(startDate);
        r.setEndDate(endDate);


        if (dailyRateUsd != null && !dailyRateUsd.isBlank()) {

            try {

                r.setDailyRateUsd(
                        new BigDecimal(dailyRateUsd.trim())
                );

            } catch (NumberFormatException e) {

                ra.addFlashAttribute(
                        "error",
                        "Invalid Daily Rate value."
                );

                return "redirect:/admin/resources";
            }
        }
        else {
            r.setDailyRateUsd(null);
        }


        com.timesheet.validator.domain.Resource savedResource = resourceRepo.save(r);


        // ============================================================
        // 2. RESOURCE_SOW
        // ============================================================

        if (sowNumber != null && !sowNumber.isBlank()) {

            String cleanSowNumber = sowNumber.trim();

            ResourceSow resourceSow =
                    resourceSowRepo
                            .findByResourceId(savedResource.getResourceId())
                            .stream()
                            .filter(rs ->
                                    cleanSowNumber.equals(
                                            rs.getSowNumber()
                                    ))
                            .findFirst()
                            .orElseGet(ResourceSow::new);


            resourceSow.setResourceId(
                    savedResource.getResourceId()
            );

            resourceSow.setSowNumber(cleanSowNumber);

            resourceSow.setAssignedTeam(
                    cleanValue(assignedTeam)
            );

            resourceSow.setSubProject(
                    cleanValue(subProject)
            );

            resourceSow.setProjectCode(
                    cleanValue(projectCode)
            );

            resourceSow.setRoleInSow(
                    cleanValue(roleInSow)
            );

            if (travelExpense != null && !travelExpense.isBlank()) {

                try {

                    resourceSow.setTravelExpense(
                            new BigDecimal(travelExpense.trim())
                    );

                } catch (NumberFormatException e) {

                    ra.addFlashAttribute(
                            "error",
                            "Invalid Travel Expense value."
                    );

                    return "redirect:/admin/resources";
                }

            } else {

                resourceSow.setTravelExpense(null);
            }


            resourceSowRepo.save(resourceSow);


            // ========================================================
            // 3. SOW_MASTER
            // ========================================================

            SowMaster sowMaster =
                    sowMasterRepo
                            .findBySowNumber(cleanSowNumber)
                            .orElseGet(SowMaster::new);

            sowMaster.setSowNumber(cleanSowNumber);

            sowMaster.setProject(
                    cleanValue(project)
            );

            sowMaster.setProjectLocation(
                    cleanValue(projectLocation)
            );

            sowMaster.setDescription(
                    cleanValue(sowDescription)
            );

            sowMaster.setStartDate(sowStartDate);
            sowMaster.setEndDate(sowEndDate);

            if (sowMaster.getActive() == null) {
                sowMaster.setActive(true);
            }

            // Existing PO fields on SOW_MASTER
            sowMaster.setPoNumber(
                    cleanValue(poNumber)
            );

            if (poValue != null && !poValue.isBlank()) {

                try {

                    sowMaster.setPoValue(
                            new BigDecimal(poValue.trim())
                    );

                } catch (NumberFormatException e) {

                    ra.addFlashAttribute(
                            "error",
                            "Invalid PO Value."
                    );

                    return "redirect:/admin/resources";
                }

            } else {

                sowMaster.setPoValue(null);
            }

            sowMasterRepo.save(sowMaster);

            // ========================================================
            // 4. PO_MASTER
            // ========================================================

            if (poNumber != null && !poNumber.isBlank()) {

                String cleanPoNumber = poNumber.trim();

                PoMaster poMaster =
                        poMasterRepo
                                .findByPoNumber(cleanPoNumber)
                                .orElseGet(PoMaster::new);


                poMaster.setPoNumber(cleanPoNumber);

                poMaster.setUpdatedPoNumber(
                        cleanValue(updatedPoNumber)
                );

                if (poValue != null &&
                        !poValue.isBlank()) {

                    poMaster.setPoValue(
                            new BigDecimal(
                                    poValue.trim()
                            )
                    );

                } else {

                    poMaster.setPoValue(null);
                }

                poMaster.setStartDate(poStartDate);
                poMaster.setEndDate(poEndDate);

                if (poMaster.getActive() == null) {
                    poMaster.setActive(true);
                }


                PoMaster savedPo =
                        poMasterRepo.save(poMaster);


                // ====================================================
                // 5. SOW_PO
                // ====================================================

                SowPoId sowPoId = new SowPoId();

                sowPoId.setSowNumber(
                        cleanSowNumber
                );

                sowPoId.setPoNumber(
                        savedPo.getPoNumber()
                );


                if (!sowPoRepo.existsById(sowPoId)) {

                    SowPo sowPo = new SowPo();

                    sowPo.setSowNumber(
                            cleanSowNumber
                    );

                    sowPo.setPoNumber(
                            savedPo.getPoNumber()
                    );

                    sowPoRepo.save(sowPo);
                }
            }

        }


        // ============================================================
        // SUCCESS
        // ============================================================

        ra.addFlashAttribute(
                "success",
                "Resource '" + savedResource.getName() + "' saved successfully."
        );

        return "redirect:/admin/resources";
    }

    private String cleanValue(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }


//    @PostMapping("/resources/delete/{id}")
//    public String deleteResource(@PathVariable Long id, RedirectAttributes ra) {
//        resourceRepo.findById(id).ifPresent(r -> {
//            resourceRepo.delete(r);
//            ra.addFlashAttribute("success", "Resource '" + r.getName() + "' deleted.");
//        });
//        return "redirect:/admin/resources";
//    }


//    @PostMapping("/resources/delete/{id}")
//    public String deleteResource(
//            @PathVariable Long id,
//            RedirectAttributes ra) {
//
//        try {
//            Optional<com.timesheet.validator.domain.Resource> resourceOpt = resourceRepo.findById(id);
//
//            if (resourceOpt.isEmpty()) {
//                ra.addFlashAttribute(
//                        "errorMessage",
//                        "Unable to delete resource."
//                );
//                return "redirect:/admin/resources";
//            }
//
//            Resource resource = (Resource) resourceOpt.get();
//
//            resourceRepo.delete((com.timesheet.validator.domain.Resource) resource);
//
//            ra.addFlashAttribute(
//                    "successMessage",
//                    "Resource deleted successfully."
//            );
//
//        } catch (Exception e) {
//
//            ra.addFlashAttribute(
//                    "errorMessage",
//                    "Unable to delete resource."
//            );
//        }
//
//        return "redirect:/admin/resources";
//    }

//    @PostMapping("/resources/delete/{id}")
//    public String deleteResource(
//            @PathVariable Long id,
//            RedirectAttributes ra) {
//
//        try {
//            Optional<com.timesheet.validator.domain.Resource> resourceOpt = resourceRepo.findById(id);
//
//            if (resourceOpt.isEmpty()) {
//                ra.addFlashAttribute(
//                        "errorMessage",
//                        "Unable to delete resource."
//                );
//                return "redirect:/admin/resources";
//            }
//
//            com.timesheet.validator.domain.Resource resource = resourceOpt.get();
//
//            resourceRepo.delete(resource);
//
//            ra.addFlashAttribute(
//                    "successMessage",
//                    "Resource deleted successfully."
//            );
//
//        } catch (Exception e) {
//
//            e.printStackTrace();   // TEMPORARY - to see actual cause
//
//            ra.addFlashAttribute(
//                    "errorMessage",
//                    "Unable to delete resource."
//            );
//        }
//
//        return "redirect:/admin/resources";
//    }


    @PostMapping("/resources/delete/{id}")
    public String deleteResource(
            @PathVariable Long id,
            RedirectAttributes ra) {

        try {

            Optional<com.timesheet.validator.domain.Resource> resourceOpt = resourceRepo.findById(id);

            if (resourceOpt.isEmpty()) {

                ra.addFlashAttribute(
                        "errorMessage",
                        "Unable to delete resource."
                );

                return "redirect:/admin/resources";
            }

            com.timesheet.validator.domain.Resource resource = resourceOpt.get();

            /*
             * Delete RESOURCE_SOW mappings first.
             *
             * RESOURCE_SOW references RESOURCE through RESOURCE_ID,
             * so the mappings must be removed before deleting RESOURCE.
             */
            List<ResourceSow> mappings =
                    resourceSowRepo.findByResourceId(
                            resource.getResourceId()
                    );

            resourceSowRepo.deleteAll(mappings);

            /*
             * Now delete the actual resource.
             */
            resourceRepo.delete(resource);

            ra.addFlashAttribute(
                    "successMessage",
                    "Resource deleted successfully."
            );

        } catch (Exception e) {

            e.printStackTrace();

            ra.addFlashAttribute(
                    "errorMessage",
                    "Unable to delete resource."
            );
        }

        return "redirect:/admin/resources";
    }


    @GetMapping("/resources/sow/edit/{id}")
    public String editSow(
            @PathVariable Long id,
            Model model) {

        SowMaster sowMaster = sowMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("SOW not found: " + id));

        SowMasterEditDto dto = new SowMasterEditDto();

        // =========================
        // SOW_MASTER
        // =========================

        dto.setId(sowMaster.getId());
        dto.setSowNumber(sowMaster.getSowNumber());
        dto.setProject(sowMaster.getProject());
        dto.setProjectLocation(sowMaster.getProjectLocation());
        dto.setDescription(sowMaster.getDescription());
        dto.setStartDate(sowMaster.getStartDate());
        dto.setEndDate(sowMaster.getEndDate());
        dto.setActive(sowMaster.getActive());


        // =========================
        // SOW_PO → PO_MASTER
        // =========================

        List<SowPo> mappings =
                sowPoRepository.findBySowNumber(sowMaster.getSowNumber());

        if (!mappings.isEmpty()) {

            SowPo sowPo = mappings.get(0);

            dto.setPoNumber(sowPo.getPoNumber());

            poMasterRepository
                    .findByPoNumber(sowPo.getPoNumber())
                    .ifPresent(po -> {

                        dto.setUpdatedPoNumber(po.getUpdatedPoNumber());
                        dto.setPoValue(po.getPoValue());
                        dto.setPoStartDate(po.getStartDate());
                        dto.setPoEndDate(po.getEndDate());

                    });
        }


        model.addAttribute("sow", dto);
        model.addAttribute("editMode", true);

        return "pages/admin/sow-form";
    }

    @PostMapping("/resources/sow/save")
    public String saveSow(
            @RequestParam Long id,
            @RequestParam String sowNumber,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String projectLocation,
            @RequestParam(required = false) String description,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,
            @RequestParam(required = false) Boolean active,

            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String updatedPoNumber,
            @RequestParam(required = false) String poValue,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate poStartDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate poEndDate,

            RedirectAttributes ra) {

        SowMaster sow = sowMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("SOW not found: " + id));

        // =========================
        // UPDATE SOW_MASTER
        // =========================

        sow.setProject(project);
        sow.setProjectLocation(projectLocation);
        sow.setDescription(description);
        sow.setStartDate(startDate);
        sow.setEndDate(endDate);
        sow.setActive(active != null ? active : false);

        sowMasterRepository.save(sow);


        // =========================
        // UPDATE PO INFORMATION
        // =========================

        List<SowPo> mappings =
                sowPoRepository.findBySowNumber(sow.getSowNumber());

        SowPo existingMapping =
                mappings.isEmpty() ? null : mappings.get(0);

        String oldPoNumber =
                existingMapping != null
                        ? existingMapping.getPoNumber()
                        : null;


        if (poNumber != null && !poNumber.isBlank()) {

            poNumber = poNumber.trim();

            // ---------------------------------
            // PO number changed
            // ---------------------------------

            if (existingMapping == null) {

                SowPo newMapping = new SowPo();

                newMapping.setSowNumber(sow.getSowNumber());
                newMapping.setPoNumber(poNumber);

                sowPoRepository.save(newMapping);

            } else if (!poNumber.equals(oldPoNumber)) {

                existingMapping.setPoNumber(poNumber);

                sowPoRepository.deleteById(
                        new SowPoId(
                                sow.getSowNumber(),
                                oldPoNumber
                        )
                );

                sowPoRepository.save(existingMapping);
            }


            // ---------------------------------
            // Update / create PO_MASTER
            // ---------------------------------

            String finalPoNumber = poNumber;
            PoMaster po = poMasterRepository
                    .findByPoNumber(poNumber)
                    .orElseGet(() -> {

                        PoMaster newPo = new PoMaster();

                        newPo.setPoNumber(finalPoNumber);

                        return newPo;
                    });

            po.setUpdatedPoNumber(
                    updatedPoNumber != null
                            ? updatedPoNumber.trim()
                            : null
            );

            if (poValue != null && !poValue.isBlank()) {
                try {
                    po.setPoValue(
                            new BigDecimal(poValue.trim())
                    );
                } catch (NumberFormatException ignored) {
                    // Keep existing value
                }
            } else {
                po.setPoValue(null);
            }

            po.setStartDate(poStartDate);
            po.setEndDate(poEndDate);

            poMasterRepository.save(po);
        }


        ra.addFlashAttribute(
                "successMessage",
                "SOW '" + sow.getSowNumber() + "' updated successfully."
        );

        return "redirect:/admin/resources";
    }

    @PostMapping("/resources/sow/delete/{id}")
    public String deleteSow(
            @PathVariable Long id,
            RedirectAttributes ra) {

        sowMasterRepository.findById(id).ifPresent(sow -> {

            String sowNumber = sow.getSowNumber();

            // Delete SOW → PO mappings first
            List<SowPo> mappings =
                    sowPoRepository.findBySowNumber(sowNumber);

            sowPoRepository.deleteAll(mappings);

            // Delete SOW_MASTER
            sowMasterRepository.delete(sow);

            ra.addFlashAttribute(
                    "successMessage",
                    "SOW '" + sowNumber + "' deleted successfully."
            );
        });

        return "redirect:/admin/resources";
    }


//    @PostMapping("/resources/sow/delete/{id}")
//    public String deleteSow(
//            @PathVariable Long id,
//            RedirectAttributes ra) {
//
//        sowMasterRepository.findById(id).ifPresent(sow -> {
//
//            String sowNumber = sow.getSowNumber();
//
//            // Delete SOW → PO mappings
//            List<SowPo> mappings =
//                    sowPoRepository.findBySowNumber(sowNumber);
//
//            sowPoRepository.deleteAll(mappings);
//
//            // Delete SOW_MASTER
//            sowMasterRepository.delete(sow);
//
//            ra.addFlashAttribute(
//                    "successMessage",
//                    "SOW '" + sowNumber + "' deleted successfully."
//            );
//        });
//
//        return "redirect:/admin/resources";
//    }


    // ══════════════════════════════════════════════════════════════════════════
    // HOLIDAYS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/holidays")
    public String holidays(Model model) {
        model.addAttribute("holidays", holidayRepo.findAll());
        return "pages/admin/holidays";
    }

    @GetMapping("/holidays/new")
    public String newHoliday(Model model) {
        model.addAttribute("holiday", new PublicHoliday());
        model.addAttribute("editMode", false);
        return "pages/admin/holiday-form";
    }

    @GetMapping("/holidays/edit/{id}")
    public String editHoliday(@PathVariable Long id, Model model) {
        PublicHoliday h = holidayRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Holiday not found: " + id));
        model.addAttribute("holiday", h);
        model.addAttribute("editMode", true);
        return "pages/admin/holiday-form";
    }

    @PostMapping("/holidays/save")
    public String saveHoliday(
            @RequestParam(required = false) Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate holidayDate,
            @RequestParam String holidayName,
            @RequestParam(required = false, defaultValue = "IN") String countryCode,
            @RequestParam(required = false) String notes,
            RedirectAttributes ra) {
        PublicHoliday h = id != null ? holidayRepo.findById(id).orElse(new PublicHoliday()) : new PublicHoliday();
        h.setHolidayDate(holidayDate);
        h.setHolidayName(holidayName.trim());
        h.setCountryCode(countryCode.trim());
        h.setNotes(notes);
        holidayRepo.save(h);
        ra.addFlashAttribute("success", "Holiday '" + h.getHolidayName() + "' saved.");
        return "redirect:/admin/holidays";
    }

    @PostMapping("/holidays/delete/{id}")
    public String deleteHoliday(@PathVariable Long id, RedirectAttributes ra) {
        holidayRepo.findById(id).ifPresent(h -> {
            holidayRepo.delete(h);
            ra.addFlashAttribute("success", "Holiday '" + h.getHolidayName() + "' deleted.");
        });
        return "redirect:/admin/holidays";
    }

    @PostMapping("/holidays/toggle/{id}")
    public String toggleHoliday(@PathVariable Long id, RedirectAttributes ra) {
        holidayRepo.findById(id).ifPresent(h -> {
            h.setEnabled(!h.isActive());
            holidayRepo.save(h);
            ra.addFlashAttribute("success",
                "Holiday '" + h.getHolidayName() + "' " + (h.isActive() ? "enabled" : "disabled") + ".");
        });
        return "redirect:/admin/holidays";
    }


    // ══════════════════════════════════════════════════════════════════════════
    // LEAVE PLANNER
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/leave-planner")
    public String leavePlanner(
            Model model,
            HttpSession session) {

        /*
         * Retrieve the current Leave Planner upload session.
         *
         * The uploaded workbook has already been parsed into
         * CellData and SheetMeta tables.
         */
        String sessionId =
                (String) session.getAttribute(
                        "leavePlannerSessionId"
                );

        boolean plannerUploaded =
                sessionId != null;

        model.addAttribute(
                "plannerUploaded",
                plannerUploaded
        );

//        if (plannerUploaded) {
//
//            /*
//             * Load sheet metadata.
//             *
//             * This is exactly how the Timesheet Viewer builds
//             * the sheet tabs.
//             */
//            model.addAttribute(
//                    "sheetMetas",
//                    sheetViewService.getSheetMetas(sessionId)
//            );
//
//            /*
//             * Initially render the first worksheet.
//             */
//            model.addAttribute(
//                    "sheet",
//                    sheetViewService.getSheet(sessionId, 0)
//            );
//
//            model.addAttribute(
//                    "leavePlannerSessionId",
//                    sessionId
//            );
//
//            model.addAttribute(
//                    "activeTab",
//                    0
//            );
//        }

        List<SheetMeta> metas =
                sheetViewService.getSheetMetas(sessionId);

        if (metas.isEmpty()) {

            session.removeAttribute("leavePlannerSessionId");

            plannerUploaded = false;

            model.addAttribute("plannerUploaded", false);

        } else {

            model.addAttribute("sheetMetas", metas);

            model.addAttribute(
                    "sheet",
                    sheetViewService.getSheet(sessionId, 0)
            );

            model.addAttribute(
                    "leavePlannerSessionId",
                    sessionId
            );

            model.addAttribute(
                    "activeTab",
                    0
            );
        }

        return "pages/admin/leave-planner";
    }

    @PostMapping("/leave-planner/upload")
    public String uploadLeavePlanner(
            @RequestParam("file") MultipartFile file,
            HttpSession session,
            RedirectAttributes ra) {

        /*
         * Validate that a file was actually selected.
         */
        if (file == null || file.isEmpty()) {

            ra.addFlashAttribute(
                    "error",
                    "Please select a Leave Planner file."
            );

            return "redirect:/admin/leave-planner";
        }

        String fileName =
                file.getOriginalFilename();

        /*
         * Validate the file extension before attempting
         * to process the workbook.
         *
         * Leave Planner currently supports only .xlsx files.
         */
        if (fileName == null ||
                !fileName.toLowerCase().endsWith(".xlsx")) {

            ra.addFlashAttribute(
                    "error",
                    "Only Excel (.xlsx) files are supported."
            );

            return "redirect:/admin/leave-planner";
        }

        /*
         * Validate the workbook structure.
         *
         * LeavePlannerValidationService validates the
         * required headers in all applicable monthly
         * Leave Planner sheets.
         */
        if (!leavePlannerValidationService.validateTemplate(file)) {

            ra.addFlashAttribute(
                    "error",
                    "Invalid Leave Planner format. Please use the approved template."
            );

            return "redirect:/admin/leave-planner";
        }

        try {

            /*
             * Parse the uploaded Leave Planner workbook using the
             * shared Excel parser.
             *
             * The workbook is persisted exactly the same way as the
             * Timesheet Viewer so that the existing SheetViewService
             * can render it.
             */
            String sessionId =
                    excelParserService.parseLeavePlanner(file);

            /*
             * Store only the session id.
             *
             * The workbook itself has already been persisted into
             * CellData and SheetMeta tables.
             */
            session.setAttribute(
                    "leavePlannerSessionId",
                    sessionId
            );

            log.info(
                    "Leave Planner uploaded successfully. Session={}",
                    sessionId
            );

            /*
             * Import the parsed planner into structured, durable LeaveEntry
             * rows so the leaves show up on every user's leave calendar.
             * (Without this the planner lives only as cells in this admin's
             * HTTP session and no other user can see it.)
             */
            LeavePlannerImportService.ImportSummary summary =
                    leavePlannerImportService.importFromSession(sessionId);

            String msg = "Leave Planner uploaded — imported "
                    + summary.getLeaveDaysImported() + " leave day(s) across "
                    + summary.getMonthlySheets() + " month sheet(s).";

            if (!summary.getUnmatchedNames().isEmpty()) {
                msg += " Unmatched names (no roster resource): "
                        + String.join(", ", summary.getUnmatchedNames()) + ".";
            }

            ra.addFlashAttribute("success", msg);

        } catch (Exception e) {

            /*
             * Handle unexpected errors while reading the
             * uploaded Excel workbook.
             */
            log.error(
                    "Unable to parse uploaded Leave Planner file: {}",
                    fileName,
                    e
            );

            ra.addFlashAttribute(
                    "error",
                    "Unable to upload Leave Planner. Please try again."
            );
        }

        return "redirect:/admin/leave-planner";
    }


    //download template
    @GetMapping("/leave-planner/template")
    public ResponseEntity<Resource> downloadLeavePlannerTemplate() throws IOException {

        Resource resource =
                new ClassPathResource("downloads/LeavePlannerTemplate.xlsx");

        if (!resource.exists()) {
            throw new RuntimeException("Leave Planner template not found.");
        }

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=LeavePlannerTemplate.xlsx"
                )
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                )
                .contentLength(resource.contentLength())
                .body(resource);
    }


    // ══════════════════════════════════════════════════════════════════════════
    // USERS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/users")
    public String users(Model model) {
        model.addAttribute("users", userRepo.findAll());
        model.addAttribute("roles", roleRepo.findAll());
        model.addAttribute("resources", resourceRepo.findAll());
        return "pages/admin/users";
    }

    @GetMapping("/users/new")
    public String newUser(Model model) {
        model.addAttribute("user", new AppUser());
        model.addAttribute("allRoles", roleRepo.findAll());
        model.addAttribute("resources", resourceRepo.findAll());
        model.addAttribute("editMode", false);
        return "pages/admin/user-form";
    }

    @GetMapping("/users/edit/{id}")
    public String editUser(@PathVariable Long id, Model model) {
        AppUser u = userRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        model.addAttribute("user", u);
        model.addAttribute("allRoles", roleRepo.findAll());
        model.addAttribute("resources", resourceRepo.findAll());
        model.addAttribute("editMode", true);
        return "pages/admin/user-form";
    }

    @PostMapping("/users/save")
    public String saveUser(
            @RequestParam(required = false) Long id,
            @RequestParam String username,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false, defaultValue = "true") boolean enabled,
            @RequestParam(required = false) Set<Long> roleIds,
            RedirectAttributes ra) {

        AppUser u = id != null ? userRepo.findById(id).orElse(new AppUser()) : new AppUser();
        u.setUsername(username.trim());
        if (password != null && !password.isBlank()) {
            u.setPassword(passwordEncoder.encode(password.trim()));
        }
        u.setFullName(fullName);
        u.setEmail(email);
        u.setResourceId(resourceId != null && !resourceId.isBlank() ? resourceId : null);
        u.setEnabled(enabled);

        if (roleIds != null && !roleIds.isEmpty()) {
            Set<Role> roles = new HashSet<>(roleRepo.findAllById(roleIds));
            u.setRoles(roles);
        }
        userRepo.save(u);
        ra.addFlashAttribute("success", "User '" + u.getUsername() + "' saved.");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/toggle/{id}")
    public String toggleUser(@PathVariable Long id, RedirectAttributes ra) {
        userRepo.findById(id).ifPresent(u -> {
            u.setEnabled(!Boolean.TRUE.equals(u.getEnabled()));
            userRepo.save(u);
            ra.addFlashAttribute("success",
                "User '" + u.getUsername() + "' " + (u.getEnabled() ? "enabled" : "disabled") + ".");
        });
        return "redirect:/admin/users";
    }
}
