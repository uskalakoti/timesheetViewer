package com.timesheet.validator.service;

import com.timesheet.validator.domain.CellData;
import com.timesheet.validator.model.CellReference;
import com.timesheet.validator.model.ProjectCodeSummary;
import com.timesheet.validator.model.ProjectSummary;
import com.timesheet.validator.model.ProjectWiseHierarchy;
import com.timesheet.validator.model.SubProjectSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses the Projectwise worksheet into an in-memory hierarchy.
 *
 * Worksheet structure:
 *
 * 1. Project Summary (Columns A:B)
 *    Used for PW-001 validation.
 *
 * 2. Project Hierarchy (Columns D:G)
 *    Used for PW-002 and PW-003 validation.
 *
 * The lower pivot report in Columns A:B is intentionally ignored.
 *
 * This class is responsible only for parsing workbook structure.
 * It performs no validation.
 */
@Service
public class ProjectWiseParser {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProjectWiseParser.class);

    /*
     * Project-wise sheet layout.
     *
     * Table 1
     *
     * A = Project
     * B = Hours
     *
     * Table 2
     *
     * D = Project
     * E = Sub Project
     * F = Project Code
     * G = Hours
     */

    private static final int PROJECT_NAME_COL = 0;
    private static final int PROJECT_HOURS_COL = 1;

    private static final int HIERARCHY_PROJECT_COL = 3;
    private static final int SUB_PROJECT_COL = 4;
    private static final int PROJECT_CODE_COL = 5;
    private static final int HIERARCHY_HOURS_COL = 6;
    private static final String PROJECT_HEADER = "Project (Mandatory)";
    private static final String ROW_LABELS = "Row Labels";
    private static final String GRAND_TOTAL = "Grand Total";

    private static final String HOURS_FIELD = "Sum of Hours";

    private static final String TOTAL_SUFFIX = " Total";

   

    public ProjectWiseHierarchy parse(List<CellData> projectWiseCells) {

        LOGGER.info("Parsing Project-wise sheet.");

        ProjectWiseHierarchy hierarchy = new ProjectWiseHierarchy();

        if (projectWiseCells == null || projectWiseCells.isEmpty()) {
            return hierarchy;
        }

        Map<Integer, Map<Integer, CellData>> grid = buildGrid(projectWiseCells);

        parseProjectTotals(grid, hierarchy);

        parseHierarchy(grid, hierarchy);

        LOGGER.info(
                "Project-wise parsing completed. Projects={}, SubProjects={}, ProjectCodes={}",
                hierarchy.getProjects().size(),
                hierarchy.getSubProjects().size(),
                hierarchy.getProjectCodes().size());

        return hierarchy;
    }

    private void parseProjectTotals(
            Map<Integer, Map<Integer, CellData>> grid,
            ProjectWiseHierarchy hierarchy) {

        boolean summaryStarted = false;

        for (Integer row : grid.keySet()) {

            String project = value(grid, row, PROJECT_NAME_COL);
            String hours = value(grid, row, PROJECT_HOURS_COL);

            // Wait until the summary table header
            if (!summaryStarted) {

                if (PROJECT_HEADER.equalsIgnoreCase(project)) {
                    summaryStarted = true;
                }

                continue;
            }

            // Stop when the second table begins
            if (ROW_LABELS.equalsIgnoreCase(project)) {
                break;
            }

            if (project.isBlank()
                    || hours.isBlank()
                    || "Grand Total".equalsIgnoreCase(project)) {
                continue;
            }

            hierarchy.addProject(
                    new ProjectSummary(
                            project,
                            parseHours(hours),
                            hoursCell(
                            row,
                            PROJECT_HOURS_COL)));
        }
    }

    private void parseHierarchy(
            Map<Integer, Map<Integer, CellData>> grid,
            ProjectWiseHierarchy hierarchy) {

        String currentProject = null;
        String currentSubProject = null;

        for (Integer row : grid.keySet()) {

            String project =
                    value(grid, row, HIERARCHY_PROJECT_COL);

            String subProject =
                    value(grid, row, SUB_PROJECT_COL);

            String projectCode =
                    value(grid, row, PROJECT_CODE_COL);

            String hours =
                    value(grid, row, HIERARCHY_HOURS_COL);

            /*
            * Ignore blank rows.
            */
            if (project.isBlank()
                    && subProject.isBlank()
                    && projectCode.isBlank()) {
                continue;
            }

            /*
            * Skip the hierarchy table header row. The header uses the same
            * column as project names (col D), e.g. "Project (Mandatory)", and
            * must not be treated as a real project/sub-project/project-code.
            */
            if (PROJECT_HEADER.equalsIgnoreCase(project)) {
                continue;
            }

            /*
            * Stop at Grand Total.
            */
            if ("Grand Total".equalsIgnoreCase(project)) {
                break;
            }

            /*
            * Ignore Project Total rows.
            *
            * Example:
            * Australia Maintenance Total
            */
            if (!project.isBlank()
                    && isProjectTotal(project)) {
                continue;
            }

            /*
            * Ignore Sub Project Total rows.
            *
            * Example:
            * APP Mod AB-OPX Total
            */
            if (!subProject.isBlank()
                    && isSubProjectTotal(subProject)) {
                continue;
            }

            /*
            * Update current project context.
            */
            if (!project.isBlank()) {
                currentProject = project;
                // Record the project cell in the Hierarchy table (col D, index 3)
                hierarchy.addHierarchyProjectCell(
                        project,
                        new CellReference(row, HIERARCHY_PROJECT_COL, "Project"));
            }

            /*
            * Update current sub project context.
            */
            if (!subProject.isBlank()) {

                currentSubProject = subProject;

                hierarchy.addSubProject(
                        new SubProjectSummary(
                                currentProject,
                                currentSubProject,
                                parseHours(hours),
                                hoursCell(
                                    row,
                                    HIERARCHY_HOURS_COL)));
            }

            /*
            * Add Project Code.
            */
            if (!projectCode.isBlank()) {

                hierarchy.addProjectCode(
                        new ProjectCodeSummary(
                                currentProject,
                                currentSubProject,
                                projectCode,
                                parseHours(hours),
                                new CellReference(
                                        row,
                                        HIERARCHY_HOURS_COL,
                                        "Sum of Hours")));
            }
        }
    }
    private Map<Integer, Map<Integer, CellData>> buildGrid(List<CellData> cells) {

        Map<Integer, Map<Integer, CellData>> grid = new TreeMap<>();

        for (CellData cell : cells) {

            grid.computeIfAbsent(
                    cell.getRowIdx(),
                    r -> new TreeMap<>())
                    .put(cell.getColIdx(), cell);
        }

        return grid;
    }


    private CellReference hoursCell(
        int row,
        int column) {

        return new CellReference(
                row,
                column,
                HOURS_FIELD);
    }

    private boolean isProjectTotal(
            String value) {

        return value.endsWith(TOTAL_SUFFIX);
    }

    private boolean isSubProjectTotal(
            String value) {

        return value.endsWith(TOTAL_SUFFIX);
    }
    

    private String value(
            Map<Integer, Map<Integer, CellData>> grid,
            int row,
            int col) {

        Map<Integer, CellData> rowData = grid.get(row);

        if (rowData == null) {
            return "";
        }

        CellData cell = rowData.get(col);

        if (cell == null || cell.getDisplayValue() == null) {
            return "";
        }

        return cell.getDisplayValue().trim();
    }

    private double parseHours(String value) {

        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (Exception ex) {
            return 0d;
        }
    }
}