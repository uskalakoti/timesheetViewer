package com.timesheet.validator.service;

import com.timesheet.validator.domain.Resource;
import com.timesheet.validator.domain.ResourceSow;
import com.timesheet.validator.domain.SowMaster;
import com.timesheet.validator.dto.ResourceImportDto;
import com.timesheet.validator.dto.ResourceImportMismatchDto;
import com.timesheet.validator.dto.ResourceImportValidationResultDto;
import com.timesheet.validator.repository.ResourceRepository;
import com.timesheet.validator.repository.ResourceSowRepository;
import com.timesheet.validator.repository.SowMasterRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResourceImportValidationService {

    private final ResourceRepository resourceRepository;
    private final ResourceSowRepository resourceSowRepository;
    private final SowMasterRepository sowMasterRepository;


    public ResourceImportValidationResultDto validate(
            List<ResourceImportDto> uploadedResources) {

        List<ResourceImportMismatchDto> mismatches =
                new ArrayList<>();

        for (ResourceImportDto uploaded : uploadedResources) {

            String employeeId = normalize(uploaded.getResourceId());

            if (employeeId == null) {
                continue;
            }

            /*
             * ========================================================
             * EMPLOYEE ID IS THE PRIMARY VALIDATION KEY
             * ========================================================
             */

            Resource existingResource =
                    resourceRepository
                            .findByResourceId(employeeId)
                            .orElse(null);

            /*
             * First-time Employee ID.
             *
             * No existing Master Data exists for this employee,
             * therefore there is nothing to compare.
             *
             * The upload can proceed without a warning.
             */
            if (existingResource == null) {
                continue;
            }


            /*
             * ========================================================
             * RESOURCE
             * ========================================================
             *
             * Validate only fields currently supplied by the
             * Resource Master workbook.
             */

            addMismatchIfChanged(
                    mismatches,
                    employeeId,
                    "Employee Name",
                    existingResource.getName(),
                    uploaded.getEmployeeName()
            );

            addMismatchIfChanged(
                    mismatches,
                    employeeId,
                    "Employee Location",
                    existingResource.getLocation(),
                    uploaded.getLocation()
            );


            /*
             * ========================================================
             * RESOURCE_SOW
             * ========================================================
             */

            List<ResourceSow> existingMappings =
                    resourceSowRepository
                            .findByResourceId(employeeId);

            String uploadedSowNumber =
                    normalize(uploaded.getSowNumber());


            /*
             * --------------------------------------------------------
             * SOW Number
             * --------------------------------------------------------
             *
             * Do NOT use existingMappings.get(0).
             *
             * A resource may have multiple SOW mappings.
             */

            if (!existingMappings.isEmpty()) {

                boolean sameSowExists =
                        uploadedSowNumber != null
                                && existingMappings.stream()
                                .map(ResourceSow::getSowNumber)
                                .map(this::normalize)
                                .anyMatch(existingSowNumber ->
                                        Objects.equals(
                                                existingSowNumber,
                                                uploadedSowNumber
                                        )
                                );

                /*
                 * If the uploaded SOW is not one of the currently
                 * mapped SOWs, report a mapping discrepancy.
                 */
                if (!sameSowExists) {

                    String existingSowNumbers =
                            existingMappings.stream()
                                    .map(ResourceSow::getSowNumber)
                                    .map(this::normalize)
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .reduce(
                                            (left, right) ->
                                                    left + ", " + right
                                    )
                                    .orElse(null);

                    addMismatchIfChanged(
                            mismatches,
                            employeeId,
                            "SOW Number",
                            existingSowNumbers,
                            uploadedSowNumber
                    );
                }
            }


            /*
             * Find the exact Employee ID + uploaded SOW mapping.
             *
             * This prevents us from accidentally comparing the
             * Designation belonging to another SOW.
             */

            ResourceSow matchingResourceSow =
                    uploadedSowNumber == null
                            ? null
                            : existingMappings.stream()
                            .filter(rs ->
                                    Objects.equals(
                                            normalize(rs.getSowNumber()),
                                            uploadedSowNumber
                                    )
                            )
                            .findFirst()
                            .orElse(null);


            /*
             * --------------------------------------------------------
             * Role / Designation
             * --------------------------------------------------------
             */

            if (matchingResourceSow != null) {

                addMismatchIfChanged(
                        mismatches,
                        employeeId,
                        "Role / Designation",
                        matchingResourceSow.getRoleInSow(),
                        uploaded.getRoleInSow()
                );
            }


            /*
             * ========================================================
             * SOW_MASTER
             * ========================================================
             *
             * Project and SOW Description are currently supplied
             * by the Resource Master parser.
             */

            if (uploadedSowNumber != null) {

                SowMaster existingSow =
                        sowMasterRepository
                                .findBySowNumber(uploadedSowNumber)
                                .orElse(null);

                if (existingSow != null) {

                    addMismatchIfChanged(
                            mismatches,
                            employeeId,
                            "Project",
                            existingSow.getProject(),
                            uploaded.getProject()
                    );

                    addMismatchIfChanged(
                            mismatches,
                            employeeId,
                            "SOW Description",
                            existingSow.getDescription(),
                            uploaded.getSowDescription()
                    );
                }
            }


            /*
             * ========================================================
             * INTENTIONALLY NOT VALIDATED YET
             * ========================================================
             *
             * Assigned Team
             * Sub-Project
             * Project Code
             * Company
             * PO Number
             * Updated PO Number
             * Daily Rate
             *
             * These fields exist in ResourceImportDto, but the
             * Resource Master parser does not currently provide a
             * confirmed source mapping for them.
             *
             * They must NOT be compared until the source mapping
             * is defined.
             */
        }


        log.info(
                "[Resource Master Validation] {} mismatches detected.",
                mismatches.size()
        );


        return ResourceImportValidationResultDto.builder()
                .hasMismatches(!mismatches.isEmpty())
                .mismatches(mismatches)
                .build();
    }


    /**
     * Compare existing Master Data with uploaded Master Data.
     *
     * null  -> null      = no change
     * null  -> value     = change
     * value -> null      = change
     * value -> same      = no change
     * value -> different = change
     */
    private void addMismatchIfChanged(
            List<ResourceImportMismatchDto> mismatches,
            String employeeId,
            String fieldName,
            Object existingValue,
            Object uploadedValue) {

        String existing =
                normalize(existingValue);

        String uploaded =
                normalize(uploadedValue);


        if (Objects.equals(existing, uploaded)) {
            return;
        }


        mismatches.add(
                ResourceImportMismatchDto.builder()
                        .employeeId(employeeId)
                        .fieldName(fieldName)
                        .existingValue(
                                existing != null
                                        ? existing
                                        : "—"
                        )
                        .uploadedValue(
                                uploaded != null
                                        ? uploaded
                                        : "—"
                        )
                        .build()
        );
    }


    /**
     * Treat null, empty strings and whitespace-only strings
     * consistently.
     */
    private String normalize(Object value) {

        if (value == null) {
            return null;
        }

        String text =
                value.toString().trim();

        return text.isEmpty()
                ? null
                : text;
    }
}