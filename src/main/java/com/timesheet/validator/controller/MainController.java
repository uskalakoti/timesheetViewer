package com.timesheet.validator.controller;

import com.timesheet.validator.domain.UploadSession;
import com.timesheet.validator.config.RuleCatalog;
import com.timesheet.validator.dto.SheetDto;
import com.timesheet.validator.dto.ValidationResultDto;
import com.timesheet.validator.repository.*;
import com.timesheet.validator.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Controller
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final ExcelParserService    parser;
    private final ValidationService     validator;
    private final SheetViewService      sheetView;
    private final UploadSessionRepository sessionRepo;
    private final ValidationIssueRepository issueRepo;
    private final PublicHolidayRepository   holidayRepo;
    private final ResourceRepository        resourceRepo;
    private final RuleCatalog               ruleCatalog;

    // ── Home ─────────────────────────────────────────────────────────────────
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("sessions",      sessionRepo.findAllByOrderByUploadedAtDesc());
        model.addAttribute("holidayCount",  holidayRepo.count());
        model.addAttribute("resourceCount", resourceRepo.count());
        model.addAttribute("holidays",      holidayRepo.findAll());
        model.addAttribute("ruleGroups",    ruleCatalog.getGroups());
        model.addAttribute("totalRuleCount", ruleCatalog.getToggleableRuleCount());
        return "pages/home";
    }

    // ── Upload ────────────────────────────────────────────────────────────────
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "rules", required = false)
                         List<String> selectedRules,
                         RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("error", "Please select an Excel (.xlsx) file.");
            return "redirect:/";
        }
        if (selectedRules == null || selectedRules.isEmpty()) {
            ra.addFlashAttribute(
                    "error",
                    "Please select at least one validation rule."
            );
            return "redirect:/";
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            ra.addFlashAttribute("error", "Only .xlsx files are supported.");
            return "redirect:/";
        }
        try {
            String sessionId = parser.parse(file, selectedRules);
            validator.validate(sessionId);
//            ra.addFlashAttribute("success", "File uploaded! Session: " + sessionId.substring(0, 8) + "…");
            return "redirect:/view/" + sessionId;
        } catch (Exception e) {
            log.error("Upload failed", e);
            ra.addFlashAttribute("error", "Upload failed: " + e.getMessage());
            return "redirect:/";
        }
    }

    // ── Viewer ────────────────────────────────────────────────────────────────
    @GetMapping("/view/{sessionId}")
    public String view(@PathVariable String sessionId,
//                       @RequestParam(defaultValue = "0") int tab,
                       @RequestParam(required = false) Integer tab,
                       @RequestParam(required = false, defaultValue = "all") String filter,
                       Model model) {
        UploadSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        long errors   = issueRepo.countBySessionIdAndSeverity(sessionId, "CRITICAL");
        long warnings = issueRepo.countBySessionIdAndSeverity(sessionId, "WARNING");

        // Apply filter
        var all = issueRepo.findBySessionId(sessionId);
        var filtered = switch (filter.toLowerCase()) {
            case "critical" -> all.stream().filter(i -> "CRITICAL".equals(i.getSeverity())).toList();
            case "warning"  -> all.stream().filter(i -> "WARNING".equals(i.getSeverity())).toList();
            default         -> all;
        };

        // Serialize all issues to JSON for client-side jsGrid (no AJAX needed)
        String issuesJson = "[]";
        try {
            issuesJson = new ObjectMapper().writeValueAsString(
                issueRepo.findBySessionId(sessionId)); // always full list for JS
        } catch (Exception e) {
            log.warn("Could not serialize issues to JSON: {}", e.getMessage());
        }

        boolean hasMandatoryErrors = all.stream()
                .anyMatch(i -> "TS-08".equals(i.getRuleId()));

        model.addAttribute("hasMandatoryErrors", hasMandatoryErrors);

        model.addAttribute("uploadSession",       session);

        // ── Active validation phase → context-sensitive rules (CR 4.5) ────────
        String phase0 = session.getValidationPhase() == null ? "TIMESHEET"
                : session.getValidationPhase();
        String activePhaseSheet;
        switch (phase0.toUpperCase()) {
            case "PIVOT":         activePhaseSheet = "Pivot";        break;
            case "PROJECT_WISE":  activePhaseSheet = "Projectwise"; break;
            case "SUMMARY":       activePhaseSheet = "Summary";     break;
            case "COMMERCIAL":    activePhaseSheet = "Commercial";  break;
            default:              activePhaseSheet = "Timesheet";
        }
        model.addAttribute("activePhaseSheet", activePhaseSheet);

        List<String> enabledRuleIds = new ArrayList<>();

        if (session.getEnabledRules() != null) {
            for (String ruleId : session.getEnabledRules().split(",")) {
                if (!ruleId.isBlank()) enabledRuleIds.add(ruleId.trim());
            }
        }

        // Applied Validations (CR 4.5): show only the ACTIVE phase's rules.
        // Always-on rules of the active sheet + any toggled rule the user has
        // selected for that sheet. No more Timesheet rules on later phases.
        List<String> activeRuleDescriptions = new ArrayList<>();
        for (RuleCatalog.RuleGroup g : ruleCatalog.getGroups()) {
            if (!activePhaseSheet.equalsIgnoreCase(g.getSheetName())) continue;
            for (RuleCatalog.RuleDef r : g.getRules()) {
                boolean applied = g.isAlwaysOn() || enabledRuleIds.contains(r.getId());
                if (applied) {
                    activeRuleDescriptions.add(r.getId() + " - " + r.getDescription());
                }
            }
        }

        model.addAttribute("enabledRules", activeRuleDescriptions);
        model.addAttribute("enabledRuleIds", enabledRuleIds);

        // Edit-Rules modal (CR 4.5): only the active phase's rule group is shown.
        model.addAttribute("ruleGroups", ruleCatalog.getGroups().stream()
                .filter(g -> activePhaseSheet.equalsIgnoreCase(g.getSheetName()))
                .toList());

        // Lazy loading: ship only lightweight per-sheet metadata (name, index,
        // row/col counts). The grids themselves are fetched per tab via
        // /api/view/{sessionId}/sheet/{index}.
        var metas = sheetView.getSheetMetas(sessionId);
        model.addAttribute("sheetMetas",    metas);

        // ── Phased validation state ──────────────────────────────────────────
        String phase = session.getValidationPhase() == null ? "TIMESHEET"
                : session.getValidationPhase();
        // Once a phase is unlocked it stays accessible in all subsequent phases
        boolean pivotUnlocked = switch (phase.toUpperCase()) {
            case "PIVOT", "PROJECT_WISE", "SUMMARY", "COMMERCIAL" -> true;
            default -> false;
        };
        boolean projectWiseUnlocked = switch (phase.toUpperCase()) {
            case "PROJECT_WISE", "SUMMARY", "COMMERCIAL" -> true;
            default -> false;
        };
        boolean summaryUnlocked = switch (phase.toUpperCase()) {
            case "SUMMARY", "COMMERCIAL" -> true;
            default -> false;
        };
        boolean commercialUnlocked = "COMMERCIAL".equalsIgnoreCase(phase);
        long timesheetErrors = issueRepo
                .countBySessionIdAndSheetNameAndSeverity(sessionId, "Timesheet", "CRITICAL");
        int pivotTabIndex = metas.stream()
                .filter(m -> "Pivot".equalsIgnoreCase(m.getSheetName()))
                .map(m -> m.getSheetIndex()).filter(java.util.Objects::nonNull)
                .findFirst().orElse(-1);

        long pivotErrors =
        issueRepo.countBySessionIdAndSheetNameAndSeverity(
                sessionId,
                "Pivot",
                "CRITICAL");

        int projectWiseTabIndex = metas.stream()
            .filter(m -> "Projectwise".equalsIgnoreCase(m.getSheetName()))
            .map(m -> m.getSheetIndex())
            .filter(x -> x != null)
            .findFirst()
            .orElse(-1);

        model.addAttribute("validationPhase",     phase);
        model.addAttribute("pivotUnlocked",       pivotUnlocked);
        model.addAttribute("pivotErrors", pivotErrors);
        model.addAttribute("timesheetErrors",     timesheetErrors);
        model.addAttribute("pivotTabIndex",       pivotTabIndex);
        long projectWiseErrors =
        issueRepo.countBySessionIdAndSheetNameAndSeverity(
                sessionId,
                "Projectwise",
                "CRITICAL");

        model.addAttribute("projectWiseUnlocked", projectWiseUnlocked);
        model.addAttribute("projectWiseTabIndex", projectWiseTabIndex);
        model.addAttribute("projectWiseErrors", projectWiseErrors);

        long summaryErrors =
                issueRepo.countBySessionIdAndSheetNameAndSeverity(
                        sessionId,
                        "Summary",
                        "CRITICAL");

        long commercialErrors =
                issueRepo.countBySessionIdAndSheetNameAndSeverity(
                        sessionId,
                        "Commercial",
                        "CRITICAL");

        int summaryTabIndex = metas.stream()
                .filter(m -> "Summary".equalsIgnoreCase(m.getSheetName()))
                .map(m -> m.getSheetIndex())
                .filter(x -> x != null)
                .findFirst()
                .orElse(-1);

        int commercialTabIndex = metas.stream()
                .filter(m -> "Commercial".equalsIgnoreCase(m.getSheetName()))
                .map(m -> m.getSheetIndex())
                .filter(x -> x != null)
                .findFirst()
                .orElse(-1);

        model.addAttribute("summaryUnlocked", summaryUnlocked);
        model.addAttribute("commercialUnlocked", commercialUnlocked);
        model.addAttribute("summaryErrors", summaryErrors);
        model.addAttribute("commercialErrors", commercialErrors);
        model.addAttribute("summaryTabIndex", summaryTabIndex);
        model.addAttribute("commercialTabIndex", commercialTabIndex);

        model.addAttribute("errorCount",    errors);
        model.addAttribute("warningCount",  warnings);
        model.addAttribute("allIssues",     filtered);      // server-side filtered list (kept for th:if checks)
        model.addAttribute("allIssuesJson", issuesJson);    // full JSON for jsGrid

        if (tab == null) {

            tab = metas.stream()
                    .filter(m ->
                            "Timesheet".equalsIgnoreCase(
                                    m.getSheetName()))
                    .map(m -> m.getSheetIndex())
                    .findFirst()
                    .orElse(0);
        }


        model.addAttribute("activeTab",     tab);
        return "pages/viewer";
    }

    // ── Phased validation: advance Timesheet → Pivot ──────────────────────────
    @PostMapping("/view/{sessionId}/advance")
    public String advancePhase(
            @PathVariable String sessionId,
            RedirectAttributes ra) {

        UploadSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        String phase = session.getValidationPhase();

        /*
        * ===========================================================
        * TIMESHEET  ->  PIVOT
        * ===========================================================
        */
        if ("TIMESHEET".equalsIgnoreCase(phase)) {

            long tsErrors = issueRepo.countBySessionIdAndSheetNameAndSeverity(
                    sessionId,
                    "Timesheet",
                    "CRITICAL");

            if (tsErrors > 0) {

                ra.addFlashAttribute(
                        "error",
                        "Cannot proceed to Pivot validation — resolve "
                                + tsErrors
                                + " unresolved Timesheet error(s) first.");

                return "redirect:/view/" + sessionId;
            }

            session.setValidationPhase("PIVOT");
            sessionRepo.save(session);

            ValidationResultDto result = validator.validate(sessionId);

            if (result.getErrorCount() > 0) {   // CR 4.3: banner only when an error is found
                ra.addFlashAttribute("error",
                        "Pivot validation found " + result.getErrorCount() + " error(s).");
            }

            int pivotTab = sheetView.getSheetMetas(sessionId)
                    .stream()
                    .filter(m -> "Pivot".equalsIgnoreCase(m.getSheetName()))
                    .map(m -> m.getSheetIndex())
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(0);

            return "redirect:/view/" + sessionId + "?tab=" + pivotTab;
        }

        /*
        * ===========================================================
        * PIVOT  ->  PROJECT_WISE
        * ===========================================================
        */
        if ("PIVOT".equalsIgnoreCase(phase)) {

            long pivotErrors = issueRepo.countBySessionIdAndSheetNameAndSeverity(
                    sessionId, "Pivot", "CRITICAL");

            if (pivotErrors > 0) {

                ra.addFlashAttribute(
                        "error",
                        "Cannot proceed to Projectwise validation — resolve "
                                + pivotErrors + " unresolved Pivot error(s) first.");

                return "redirect:/view/" + sessionId;
            }

            session.setValidationPhase("PROJECT_WISE");
            sessionRepo.save(session);

            ValidationResultDto result = validator.validate(sessionId);

            if (result.getErrorCount() > 0) {   // CR 4.3
                ra.addFlashAttribute("error",
                        "Projectwise validation found " + result.getErrorCount() + " error(s).");
            }

            int projectWiseTab = sheetView.getSheetMetas(sessionId)
                    .stream()
                    .filter(m -> "Projectwise".equalsIgnoreCase(m.getSheetName()))
                    .map(m -> m.getSheetIndex())
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(0);

            return "redirect:/view/" + sessionId + "?tab=" + projectWiseTab;
        }

        /*
        * ===========================================================
        * PROJECT_WISE  ->  SUMMARY
        * ===========================================================
        */
        if ("PROJECT_WISE".equalsIgnoreCase(phase)) {

            long projectWiseErrors = issueRepo.countBySessionIdAndSheetNameAndSeverity(
                    sessionId, "Projectwise", "CRITICAL");

            if (projectWiseErrors > 0) {

                ra.addFlashAttribute(
                        "error",
                        "Cannot proceed to Summary validation — resolve "
                                + projectWiseErrors + " unresolved Projectwise error(s) first.");

                return "redirect:/view/" + sessionId;
            }

            session.setValidationPhase("SUMMARY");
            sessionRepo.save(session);

            ValidationResultDto summaryResult = validator.validate(sessionId);

            if (summaryResult.getErrorCount() > 0) {   // CR 4.3
                ra.addFlashAttribute("error",
                        "Summary validation found " + summaryResult.getErrorCount() + " error(s).");
            }

            int summaryTab = sheetView.getSheetMetas(sessionId)
                    .stream()
                    .filter(m -> "Summary".equalsIgnoreCase(m.getSheetName()))
                    .map(m -> m.getSheetIndex())
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(0);

            return "redirect:/view/" + sessionId + "?tab=" + summaryTab;
        }

        /*
        * ===========================================================
        * SUMMARY  ->  COMMERCIAL
        * ===========================================================
        */
        if ("SUMMARY".equalsIgnoreCase(phase)) {

            long summaryErrors = issueRepo.countBySessionIdAndSheetNameAndSeverity(
                    sessionId, "Summary", "CRITICAL");

            if (summaryErrors > 0) {

                ra.addFlashAttribute(
                        "error",
                        "Cannot proceed to Commercial validation — resolve "
                                + summaryErrors + " unresolved Summary error(s) first.");

                return "redirect:/view/" + sessionId;
            }

            session.setValidationPhase("COMMERCIAL");
            sessionRepo.save(session);

            ValidationResultDto commercialResult = validator.validate(sessionId);

            if (commercialResult.getErrorCount() > 0) {   // CR 4.3
                ra.addFlashAttribute("error",
                        "Commercial validation found " + commercialResult.getErrorCount() + " error(s).");
            }

            int commercialTab = sheetView.getSheetMetas(sessionId)
                    .stream()
                    .filter(m -> "Commercial".equalsIgnoreCase(m.getSheetName()))
                    .map(m -> m.getSheetIndex())
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(0);

            return "redirect:/view/" + sessionId + "?tab=" + commercialTab;
        }

        // Fallback: should not reach here
        ra.addFlashAttribute("error", "Unknown validation phase: " + phase);
        return "redirect:/view/" + sessionId;
    }

    // ── Phased validation: go back one stage and land on that stage's sheet ──
    @PostMapping("/view/{sessionId}/reset-phase")
    public String resetPhase(@PathVariable String sessionId, RedirectAttributes ra) {
        UploadSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        String currentPhase = session.getValidationPhase();
        String targetPhase;
        if ("SUMMARY".equalsIgnoreCase(currentPhase)) {
            targetPhase = "PROJECT_WISE";
        } else if ("COMMERCIAL".equalsIgnoreCase(currentPhase)) {
            targetPhase = "SUMMARY";
        } else if ("PROJECT_WISE".equalsIgnoreCase(currentPhase)) {
            targetPhase = "PIVOT";
        } else {
            targetPhase = "TIMESHEET";
        }
        session.setValidationPhase(targetPhase);
        sessionRepo.save(session);
        validator.validate(sessionId);

        // Land on the previous stage's sheet instead of defaulting to Timesheet.
        String targetSheet = switch (targetPhase) {
            case "PIVOT" -> "Pivot";
            case "PROJECT_WISE" -> "Projectwise";
            case "SUMMARY" -> "Summary";
            case "COMMERCIAL" -> "Commercial";
            default -> "Timesheet";
        };
        int backTab = sheetView.getSheetMetas(sessionId).stream()
                .filter(m -> targetSheet.equalsIgnoreCase(m.getSheetName()))
                .map(m -> m.getSheetIndex())
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(0);

        ra.addFlashAttribute("success", "Phase reset.");
        return "redirect:/view/" + sessionId + "?tab=" + backTab;
    }

    // ── Re-validate ───────────────────────────────────────────────────────────
    @PostMapping("/validate/{sessionId}")
    public String validate(@PathVariable String sessionId, RedirectAttributes ra) {

        log.info("REVALIDATE CLICKED FOR SESSION = {}", sessionId);

        ValidationResultDto result = validator.validate(sessionId);

        log.info("REVALIDATION COMPLETED. Errors={} Warnings={}",
                result.getErrorCount(),
                result.getWarningCount());

        ra.addFlashAttribute(result.isPassed() ? "success" : "warning",
                result.isPassed() ? "Validation passed — no critical errors."
                        : "Found " + result.getErrorCount() + " error(s) and "
                          + result.getWarningCount() + " warning(s).");
        return "redirect:/view/" + sessionId;
    }


    //update rules
    @PostMapping("/update-rules/{sessionId}")
    public String updateRules(@PathVariable String sessionId,
                              @RequestParam(required = false)
                              List<String> rules,
                              RedirectAttributes ra) {

        log.info("UPDATE RULES CALLED");
        log.info("RULES RECEIVED = {}", rules);

        UploadSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() ->
                        new RuntimeException("Session not found"));

        // Determine the active phase so we only touch that sheet's rules and
        // never wipe toggled rules from other phases (CR 4.5).
        String uPhase = session.getValidationPhase() == null ? "TIMESHEET"
                : session.getValidationPhase();
        String uSheet;
        switch (uPhase.toUpperCase()) {
            case "PIVOT":        uSheet = "Pivot";        break;
            case "PROJECT_WISE": uSheet = "Projectwise"; break;
            case "SUMMARY":      uSheet = "Summary";     break;
            case "COMMERCIAL":   uSheet = "Commercial";  break;
            default:             uSheet = "Timesheet";
        }

        // map rule id -> sheet
        java.util.Map<String, String> idToSheet = new java.util.HashMap<>();
        for (RuleCatalog.RuleGroup g : ruleCatalog.getGroups()) {
            for (RuleCatalog.RuleDef r : g.getRules()) {
                idToSheet.put(r.getId(), g.getSheetName());
            }
        }

        // Keep toggled rules from *other* sheets untouched.
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        if (session.getEnabledRules() != null) {
            for (String id : session.getEnabledRules().split(",")) {
                String s = idToSheet.get(id.trim());
                if (id.isBlank()) continue;
                if (s == null || !uSheet.equalsIgnoreCase(s)) kept.add(id.trim());
            }
        }
        if (rules != null) {
            for (String id : rules) if (!id.isBlank()) kept.add(id.trim());
        }

        if (kept.isEmpty()) {
            ra.addFlashAttribute("error",
                    "Please select at least one validation rule.");
            return "redirect:/view/" + sessionId;
        }

        session.setEnabledRules(String.join(",", kept));

        sessionRepo.save(session);

        ValidationResultDto result =
                validator.validate(sessionId);

        ra.addFlashAttribute(
                result.isPassed() ? "success" : "warning",
                result.isPassed()
                        ? "Validation passed."
                        : "Validation completed. Found "
                        + result.getErrorCount()
                        + " errors and "
                        + result.getWarningCount()
                        + " warnings."
        );

        return "redirect:/view/" + sessionId;
    }

    // ── Export Issues as CSV ──────────────────────────────────────────────────
    // NOTE: URL uses /export-csv/ to avoid the // path segment that Spring
    // Security rejects when using /export/{id}/issues.csv  pattern.
    @GetMapping("/export-csv/{sessionId}")
    public void exportCsv(@PathVariable String sessionId,
                          @RequestParam(required = false, defaultValue = "all") String filter,
                          HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"issues-" + sessionId.substring(0, 8) + ".csv\"");

        var all = issueRepo.findBySessionId(sessionId);
        var issues = switch (filter.toLowerCase()) {
            case "critical" -> all.stream().filter(i -> "CRITICAL".equals(i.getSeverity())).toList();
            case "warning"  -> all.stream().filter(i -> "WARNING".equals(i.getSeverity())).toList();
            default         -> all;
        };

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8))) {
            pw.println("Rule ID,Severity,Sheet,Row,Field,Message");
            for (var issue : issues) {
                pw.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        csv(issue.getRuleId()), csv(issue.getSeverity()),
                        csv(issue.getSheetName()),
                        (issue.getRowIdx() != null && issue.getRowIdx() >= 0
                                ? "Row " + (issue.getRowIdx() + 1) : "Multiple"),
                        csv(issue.getFieldName()), csv(issue.getMessage()));
            }
        }
    }

    // ── Login page ────────────────────────────────────────────────────────────
    @GetMapping("/login")
    public String login() { return "pages/login"; }

    private String csv(String s) {
        return s == null ? "" : s.replace("\"", "\"\"");
    }

    @GetMapping("/403")
    public String forbidden() {
        return "pages/403";
    }

}