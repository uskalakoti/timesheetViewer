package com.timesheet.validator.service;

import com.timesheet.validator.domain.PoMaster;
import com.timesheet.validator.domain.SowMaster;
import com.timesheet.validator.domain.SowPo;
import com.timesheet.validator.dto.SowImportDto;
import com.timesheet.validator.dto.SowImportMismatchDto;
import com.timesheet.validator.dto.SowImportValidationResultDto;
import com.timesheet.validator.repository.PoMasterRepository;
import com.timesheet.validator.repository.SowMasterRepository;
import com.timesheet.validator.repository.SowPoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class SowImportValidationService {

    private final SowMasterRepository sowMasterRepository;
    private final SowPoRepository sowPoRepository;
    private final PoMasterRepository poMasterRepository;


    /**
     * Validates an uploaded SOW Master workbook against
     * the currently persisted Master Data.
     *
     * Primary business key:
     *
     *     SOW_NUMBER
     *
     * Existing SOW numbers are compared field-by-field.
     *
     * New SOW numbers are NOT treated as mismatches.
     */
    public SowImportValidationResultDto validate(
            List<SowImportDto> uploadedSows) {

        List<SowImportMismatchDto> mismatches =
                new ArrayList<>();

        if (uploadedSows == null || uploadedSows.isEmpty()) {

            return SowImportValidationResultDto.builder()
                    .hasMismatches(false)
                    .mismatches(mismatches)
                    .build();
        }


        for (SowImportDto uploaded : uploadedSows) {

            /*
             * ============================================================
             * SOW NUMBER
             * ============================================================
             *
             * SOW Number is the primary identifier.
             */
            String sowNumber =
                    normalize(uploaded.getSowNumber());

            /*
             * Ignore invalid/blank rows.
             */
            if (sowNumber == null) {
                continue;
            }


            /*
             * ============================================================
             * FIND EXISTING SOW
             * ============================================================
             */
            SowMaster existingSow =
                    sowMasterRepository
                            .findBySowNumber(sowNumber)
                            .orElse(null);


            /*
             * ============================================================
             * NEW SOW
             * ============================================================
             *
             * If the SOW Number doesn't exist yet, there is
             * nothing to compare.
             *
             * saveSows() will create the new record later
             * when the upload is proceeded.
             */
            if (existingSow == null) {
                continue;
            }


            /*
             * ============================================================
             * SOW_MASTER VALIDATION
             * ============================================================
             */

            /*
             * 1. Project
             */
            addMismatchIfChanged(
                    mismatches,
                    sowNumber,
                    "Project",
                    existingSow.getProject(),
                    uploaded.getProject()
            );


            /*
             * 2. Project Location
             */
            addMismatchIfChanged(
                    mismatches,
                    sowNumber,
                    "Project Location",
                    existingSow.getProjectLocation(),
                    uploaded.getProjectLocation()
            );


            /*
             * 3. SOW Description
             */
            addMismatchIfChanged(
                    mismatches,
                    sowNumber,
                    "SOW Description",
                    existingSow.getDescription(),
                    uploaded.getSowDescription()
            );


            /*
             * ============================================================
             * PO VALIDATION
             * ============================================================
             *
             * PO information is stored through:
             *
             * SOW_MASTER
             *      ↓
             * SOW_PO
             *      ↓
             * PO_MASTER
             */
            validatePoInformation(
                    uploaded,
                    sowNumber,
                    mismatches
            );
        }


        log.info(
                "[SOW Master Validation] {} mapping discrepancies detected.",
                mismatches.size()
        );


        return SowImportValidationResultDto.builder()
                .hasMismatches(!mismatches.isEmpty())
                .mismatches(mismatches)
                .build();
    }


    /**
     * Validates PO-related fields for an existing SOW.
     */
    private void validatePoInformation(
            SowImportDto uploaded,
            String sowNumber,
            List<SowImportMismatchDto> mismatches) {


        /*
         * ============================================================
         * FIND CURRENT SOW → PO MAPPING
         * ============================================================
         */
        List<SowPo> existingMappings =
                sowPoRepository.findBySowNumber(sowNumber);


        String existingPoNumber = null;

        PoMaster existingPo = null;


        /*
         * Currently the data model allows multiple PO mappings
         * for a SOW.
         *
         * For the current Resource/SOW Master implementation,
         * use the first active mapping returned.
         */
        if (existingMappings != null
                && !existingMappings.isEmpty()) {

            SowPo existingMapping =
                    existingMappings.get(0);

            existingPoNumber =
                    normalize(
                            existingMapping.getPoNumber()
                    );


            /*
             * Find the corresponding PO_MASTER record.
             */
            if (existingPoNumber != null) {

                existingPo =
                        poMasterRepository
                                .findByPoNumber(existingPoNumber)
                                .orElse(null);
            }
        }


        /*
         * ============================================================
         * PO NUMBER
         * ============================================================
         */
        addMismatchIfChanged(
                mismatches,
                sowNumber,
                "PO Number",
                existingPoNumber,
                uploaded.getPoNumber()
        );


        /*
         * ============================================================
         * UPDATED PO NUMBER
         * ============================================================
         */
        addMismatchIfChanged(
                mismatches,
                sowNumber,
                "Updated PO Number",
                existingPo != null
                        ? existingPo.getUpdatedPoNumber()
                        : null,
                uploaded.getUpdatedPoNumber()
        );


        /*
         * ============================================================
         * PO START DATE
         * ============================================================
         */
        addMismatchIfChanged(
                mismatches,
                sowNumber,
                "PO Start Date",
                existingPo != null
                        ? existingPo.getStartDate()
                        : null,
                uploaded.getPoStartDate()
        );


        /*
         * ============================================================
         * PO END DATE
         * ============================================================
         */
        addMismatchIfChanged(
                mismatches,
                sowNumber,
                "PO End Date",
                existingPo != null
                        ? existingPo.getEndDate()
                        : null,
                uploaded.getPoEndDate()
        );


        /*
         * ============================================================
         * PO VALUE
         * ============================================================
         */
        addMismatchIfChanged(
                mismatches,
                sowNumber,
                "PO Value",
                existingPo != null
                        ? existingPo.getPoValue()
                        : null,
                uploaded.getPoValue()
        );
    }


    /**
     * Adds a mismatch when the existing and uploaded values differ.
     *
     * Comparison rules:
     *
     * null  → null   = no change
     * null  → value   = change
     * value → null    = change
     * value → same    = no change
     * value → other   = change
     */
    private void addMismatchIfChanged(
            List<SowImportMismatchDto> mismatches,
            String sowNumber,
            String fieldName,
            Object existingValue,
            Object uploadedValue) {


        String existing =
                normalize(existingValue);

        String uploaded =
                normalize(uploadedValue);


        /*
         * Both values are blank/null.
         *
         * Nothing changed.
         */
        if (existing == null && uploaded == null) {
            return;
        }


        /*
         * One value changed or both values are different.
         */
        if (!Objects.equals(existing, uploaded)) {

            mismatches.add(
                    SowImportMismatchDto.builder()
                            .sowNumber(sowNumber)
                            .fieldName(fieldName)
                            .existingValue(existing)
                            .uploadedValue(uploaded)
                            .build()
            );
        }
    }


    /**
     * Normalizes values before comparison.
     *
     * This ensures:
     *
     * null
     * ""
     * "   "
     *
     * are treated as the same empty value.
     */
    private String normalize(Object value) {

        if (value == null) {
            return null;
        }

        String result =
                value.toString().trim();

        return result.isEmpty()
                ? null
                : result;
    }
}