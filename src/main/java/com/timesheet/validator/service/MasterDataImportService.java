package com.timesheet.validator.service;

import com.timesheet.validator.domain.*;
import com.timesheet.validator.dto.*;
import com.timesheet.validator.repository.ResourceRepository;
import com.timesheet.validator.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import com.timesheet.validator.dto.SowImportValidationResultDto;
import com.timesheet.validator.dto.SowImportDto;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MasterDataImportService {

    private final ExcelParserService excelParserService;

    private final ResourceRepository resourceRepository;

    private final ResourceSowRepository resourceSowRepository;

    private final SowMasterRepository sowMasterRepository;

    private final PoMasterRepository poMasterRepository;

    private final SowPoRepository sowPoRepository;

    private final ResourceImportValidationService resourceImportValidationService;

    private final SowImportValidationService sowImportValidationService;

    /**
     * Imports Resource Master workbook.
     */
//    public List<ResourceImportDto> importResourceWorkbook(
//            MultipartFile workbook) throws Exception {
//
//        log.info("Importing Resource workbook : {}",
//                workbook.getOriginalFilename());
//
//
//        List<ResourceImportDto> resources =
//                excelParserService.parseResourceWorkbook(workbook);
//
//        saveResources(resources);
//
//        return resources;
//    }



//    public ResourceImportValidationResultDto importResourceWorkbook(
//            MultipartFile workbook) throws Exception {
//
//        log.info("Importing Resource workbook : {}",
//                workbook.getOriginalFilename());
//
//        /*
//         * ------------------------------------------------------------
//         * STEP 1 — Parse uploaded workbook
//         * ------------------------------------------------------------
//         */
//        List<ResourceImportDto> resources =
//                excelParserService.parseResourceWorkbook(workbook);
//
//        log.info(
//                "Parsed {} Resource records from uploaded workbook.",
//                resources.size()
//        );
//
//        /*
//         * ------------------------------------------------------------
//         * STEP 2 — Validate against existing Master Data
//         * ------------------------------------------------------------
//         *
//         * IMPORTANT:
//         *
//         * We validate BEFORE saveResources().
//         *
//         * This ensures the existing Master Data remains untouched
//         * while the user reviews any detected discrepancies.
//         */
//        ResourceImportValidationResultDto validationResult =
//                resourceImportValidationService.validate(resources);
//
//        /*
//         * ------------------------------------------------------------
//         * STEP 3 — No mismatches
//         * ------------------------------------------------------------
//         *
//         * This is either:
//         *
//         * 1. First upload
//         * 2. Existing data matches uploaded data
//         *
//         * In both cases it is safe to persist immediately.
//         */
//        if (!validationResult.isHasMismatches()) {
//
//            log.info(
//                    "No Resource Master mapping discrepancies detected. " +
//                            "Proceeding with persistence."
//            );
//
//            saveResources(resources);
//
//            /*
//             * Return the validation result.
//             *
//             * hasMismatches = false
//             * mismatches = empty
//             */
//            return validationResult;
//        }
//
//
//        /*
//         * ------------------------------------------------------------
//         * STEP 4 — Mismatches detected
//         * ------------------------------------------------------------
//         *
//         * DO NOT call saveResources().
//         *
//         * The caller will use the mismatch information to show the
//         * review/confirmation screen.
//         */
//        log.warn(
//                "Resource Master upload contains {} mapping discrepancies. " +
//                        "Persistence has been skipped until user confirmation.",
//                validationResult.getMismatches().size()
//        );
//
//        return validationResult;
//    }


    /**
     * Parses and validates Resource Master workbook.
     *
     * IMPORTANT:
     * If mapping discrepancies are detected, Resource Master data
     * is NOT persisted until the administrator confirms the changes.
     */
    public ResourceImportResultDto importResourceWorkbook(
            MultipartFile workbook) throws Exception {

        log.info(
                "Importing Resource workbook : {}",
                workbook.getOriginalFilename()
        );

        List<ResourceImportDto> resources =
                excelParserService.parseResourceWorkbook(workbook);

        /*
         * ------------------------------------------------------------
         * VALIDATE BEFORE PERSISTENCE
         * ------------------------------------------------------------
         */
//        List<ResourceImportMismatchDto> mismatches =
//                resourceImportValidationService.validate(resources);
//
//        if (!mismatches.isEmpty()) {
//
//            log.warn(
//                    "Resource Master upload contains {} mapping discrepancies.",
//                    mismatches.size()
//            );
//
//            log.warn(
//                    "Persistence has been skipped until user confirmation."
//            );
//
//            return new ResourceImportResultDto(
//                    resources,
//                    mismatches,
//                    true
//            );
//        }

        ResourceImportValidationResultDto validationResult =
                resourceImportValidationService.validate(resources);

        List<ResourceImportMismatchDto> mismatches =
                validationResult.getMismatches();

        if (!mismatches.isEmpty()) {

            log.warn(
                    "Resource Master upload contains {} mapping discrepancies.",
                    mismatches.size()
            );

            log.warn(
                    "Persistence has been skipped until user confirmation."
            );

            return new ResourceImportResultDto(
                    resources,
                    mismatches,
                    true
            );
        }


        /*
         * ------------------------------------------------------------
         * NO MISMATCHES
         * ------------------------------------------------------------
         *
         * First upload OR same data re-upload.
         * Existing behavior remains unchanged.
         */
        saveResources(resources);

        return new ResourceImportResultDto(
                resources,
                List.of(),
                false
        );
    }



    /**
     * Imports SOW Master workbook.
     */
//    public List<SowImportDto> importSowWorkbook(
//            MultipartFile workbook) throws Exception {
//
//        log.info("Importing SOW workbook : {}",
//                workbook.getOriginalFilename());
//
////        return excelParserService.parseSowWorkbook(workbook);
//
//        List<SowImportDto> sows =
//                excelParserService.parseSowWorkbook(workbook);
//
//        saveSows(sows);
//
//        return sows;
//    }


    /**
     * Imports SOW Master workbook.
     *
     * The workbook is first parsed and then validated against
     * existing SOW Master data using SOW Number as the
     * primary business key.
     */
    public List<SowImportDto> importSowWorkbook(
            MultipartFile workbook) throws Exception {

        log.info(
                "Importing SOW workbook : {}",
                workbook.getOriginalFilename()
        );

        /*
         * ============================================================
         * STEP 1 — PARSE
         * ============================================================
         */
        List<SowImportDto> sows =
                excelParserService.parseSowWorkbook(workbook);


        /*
         * ============================================================
         * STEP 2 — VALIDATE
         * ============================================================
         *
         * IMPORTANT:
         *
         * Validation happens BEFORE saveSows().
         */
        SowImportValidationResultDto validationResult =
                sowImportValidationService.validate(sows);


        /*
         * ============================================================
         * STEP 3 — CHECK FOR MISMATCHES
         * ============================================================
         */
        if (validationResult.isHasMismatches()) {

            log.warn(
                    "SOW Master upload contains {} mapping discrepancies.",
                    validationResult.getMismatches().size()
            );

            /*
             * DO NOT call saveSows() here.
             *
             * The next implementation step will store the
             * pending upload and display the review page.
             */
            throw new SowImportValidationException(
                    sows,
                    validationResult
            );
        }


        /*
         * ============================================================
         * STEP 4 — NO MISMATCHES
         * ============================================================
         *
         * New SOW Numbers and unchanged existing SOW Numbers
         * can be persisted immediately.
         */
        saveSows(sows);

        return sows;
    }


    /**
     * Persists a previously validated Resource Master import
     * after administrator confirmation.
     */
    @Transactional
    public void confirmResourceImport(
            List<ResourceImportDto> resources) {

        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException(
                    "No pending Resource Master data found."
            );
        }

        log.info(
                "Persisting confirmed Resource Master import containing {} records.",
                resources.size()
        );

        saveResources(resources);
    }


//    @Transactional
//    private void saveResources(List<ResourceImportDto> resources) {
//
//        for (ResourceImportDto dto : resources) {
//
//            /*
//             * ------------------------------------------------------------
//             * RESOURCE
//             * ------------------------------------------------------------
//             * Business Key = RESOURCE_ID (Employee ID)
//             */
//            Resource resource = resourceRepository
//                    .findByResourceId(dto.getResourceId())
//                    .orElseGet(Resource::new);
//
//            resource.setResourceId(dto.getResourceId());
//            resource.setName(dto.getEmployeeName());
//
//            resource.setLocation(dto.getLocation());
//            resource.setCompany(dto.getCompany());
//
//            resource.setCompany(
//                    dto.getCompany() != null && !dto.getCompany().isBlank()
//                            ? dto.getCompany()
//                            : "Atain"
//            );
//
//            resource.setWorkingHoursPerDay(8.0);
//
//            resourceRepository.save(resource);
//
//            /*
//             * ------------------------------------------------------------
//             * RESOURCE_SOW
//             * ------------------------------------------------------------
//             * Business Key =
//             * RESOURCE_ID + SOW_NUMBER
//             */
//            ResourceSowId id = new ResourceSowId(
//                    dto.getResourceId(),
//                    dto.getSowNumber()
//            );
//
//            ResourceSow resourceSow = resourceSowRepository
//                    .findById(id)
//                    .orElseGet(ResourceSow::new);
//
//            resourceSow.setResourceId(dto.getResourceId());
//
//            resourceSow.setSowNumber(dto.getSowNumber());
//
//            /*
//             * Designation from Resource Master workbook
//             * is stored as the role for this Resource-SOW relationship.
//             */
//            resourceSow.setRoleInSow(dto.getRoleInSow());
//
//            /*
//             * Will remain null until imported
//             * from the correct master source.
//             */
//            resourceSow.setAssignedTeam(dto.getAssignedTeam());
//
//            resourceSow.setProjectCode(dto.getProjectCode());
//
//            resourceSow.setSubProject(dto.getSubProject());
//
//            resourceSow.setTravelExpense(dto.getTravelExpense());
//
//            resourceSowRepository.save(resourceSow);
//        }
//
//        log.info("Saved {} Resource records.", resources.size());
//    }



    @Transactional
    private void saveResources(List<ResourceImportDto> resources) {

        for (ResourceImportDto dto : resources) {

            String employeeId = dto.getResourceId();

            /*
             * ============================================================
             * RESOURCE
             * ============================================================
             *
             * Business Key = RESOURCE_ID / Employee ID
             *
             * Currently mapped fields:
             *
             * - Employee ID
             * - Employee Name
             * - Employee Location
             *
             * Do NOT overwrite unmapped fields such as Company,
             * Daily Rate, Working Hours, etc.
             */

            Resource resource = resourceRepository
                    .findByResourceId(employeeId)
                    .orElseGet(Resource::new);

            resource.setResourceId(employeeId);

            /*
             * Employee Name
             */
            resource.setName(dto.getEmployeeName());

            /*
             * Employee Location
             */
            resource.setLocation(dto.getLocation());

            /*
             * IMPORTANT:
             *
             * Company is NOT currently mapped from the Resource Master
             * Excel source.
             *
             * Therefore we intentionally do NOT call:
             *
             * resource.setCompany(dto.getCompany());
             *
             * Existing Company value is preserved.
             */

            /*
             * Daily Rate is not currently mapped from the Resource
             * Master parser.
             *
             * Existing value is preserved.
             */

            /*
             * Working Hours are not supplied by the workbook.
             *
             * Existing value is preserved.
             */

            resourceRepository.save(resource);


            /*
             * ============================================================
             * RESOURCE_SOW
             * ============================================================
             *
             * Business Key =
             *
             * RESOURCE_ID + SOW_NUMBER
             */

            String sowNumber = dto.getSowNumber();

            /*
             * If the uploaded row doesn't contain a SOW Number,
             * there is no Resource-SOW mapping to persist.
             */
            if (sowNumber == null || sowNumber.isBlank()) {
                continue;
            }

            ResourceSowId id =
                    new ResourceSowId(
                            employeeId,
                            sowNumber
                    );

            ResourceSow resourceSow =
                    resourceSowRepository
                            .findById(id)
                            .orElseGet(ResourceSow::new);

            resourceSow.setResourceId(employeeId);

            resourceSow.setSowNumber(sowNumber);

            /*
             * Role / Designation
             *
             * This is currently mapped from the Excel
             * Designation column.
             */
            resourceSow.setRoleInSow(
                    dto.getRoleInSow()
            );


            /*
             * IMPORTANT:
             *
             * Assigned Team is currently NOT mapped from Excel.
             *
             * Do NOT overwrite the existing value with null.
             */

            /*
             * Project Code is currently NOT mapped.
             *
             * Existing value is preserved.
             */

            /*
             * Sub-Project is currently NOT mapped.
             *
             * Existing value is preserved.
             */

            /*
             * Travel Expense is currently NOT mapped.
             *
             * Existing value is preserved.
             */

            resourceSowRepository.save(resourceSow);

            log.info(
                    "[Resource Master] Persisting SOW_MASTER: sowNumber={}, project={}, description={}",
                    sowNumber,
                    dto.getProject(),
                    dto.getSowDescription()
            );


            /*
             * ============================================================
             * SOW_MASTER
             * ============================================================
             *
             * Project and SOW Description are currently populated
             * by the Resource Master parser.
             *
             * Therefore these values must be persisted here when
             * the administrator clicks Proceed.
             */

            sowMasterRepository
                    .findBySowNumber(sowNumber)
                    .ifPresentOrElse(
                            existingSow -> {

                                /*
                                 * Project
                                 */
                                existingSow.setProject(
                                        dto.getProject()
                                );

                                /*
                                 * SOW Description
                                 */
                                existingSow.setDescription(
                                        dto.getSowDescription()
                                );

                                sowMasterRepository.save(existingSow);
                            },

                            () -> {

                                /*
                                 * The SOW does not currently exist
                                 * in SOW_MASTER.
                                 *
                                 * Create it because the uploaded
                                 * Resource Master contains this SOW.
                                 */
                                SowMaster newSow =
                                        SowMaster.builder()
                                                .sowNumber(sowNumber)
                                                .project(dto.getProject())
                                                .description(
                                                        dto.getSowDescription()
                                                )
                                                .active(Boolean.TRUE)
                                                .build();

                                sowMasterRepository.save(newSow);
                            }
                    );

        }

        log.info(
                "Saved {} Resource records.",
                resources.size()
        );

    }



    @Transactional
    private void saveSows(List<SowImportDto> sowDtos) {

        for (SowImportDto dto : sowDtos) {

            String sowNumber = dto.getSowNumber();

            /*
             * Skip invalid rows.
             */
            if (sowNumber == null || sowNumber.isBlank()) {
                continue;
            }

            /*
             * ------------------------------------------------------------
             * SOW_MASTER
             * ------------------------------------------------------------
             * Business Key = SOW_NUMBER
             */
            SowMaster sowMaster = sowMasterRepository
                    .findBySowNumber(sowNumber)
                    .orElseGet(SowMaster::new);

            sowMaster.setSowNumber(sowNumber);
            sowMaster.setProject(dto.getProject());
            sowMaster.setProjectLocation(dto.getProjectLocation());
            sowMaster.setDescription(dto.getSowDescription());

            sowMaster.setStartDate(dto.getSowStartDate());
            sowMaster.setEndDate(dto.getSowEndDate());

            /*
             * Keep ACTIVE true for imported master data.
             */
            sowMaster.setActive(Boolean.TRUE);

            sowMasterRepository.save(sowMaster);

            /*
             * ------------------------------------------------------------
             * PO_MASTER
             * SOW_PO
             * ------------------------------------------------------------
             * Save only if a valid PO Number is present.
             */
            String poNumber = dto.getPoNumber();

            if (poNumber != null && !poNumber.isBlank()) {

                /*
                 * ------------------------------------------------------------
                 * PO_MASTER
                 * ------------------------------------------------------------
                 * Business Key = PO_NUMBER
                 */
                PoMaster poMaster = poMasterRepository
                        .findByPoNumber(poNumber)
                        .orElseGet(PoMaster::new);

                poMaster.setPoNumber(poNumber);
                poMaster.setUpdatedPoNumber(dto.getUpdatedPoNumber());
                poMaster.setPoValue(dto.getPoValue());
                poMaster.setStartDate(dto.getPoStartDate());
                poMaster.setEndDate(dto.getPoEndDate());
                poMaster.setActive(Boolean.TRUE);

                poMasterRepository.save(poMaster);

                /*
                 * ------------------------------------------------------------
                 * SOW_PO
                 * ------------------------------------------------------------
                 * Business Key = SOW_NUMBER + PO_NUMBER
                 */
                SowPoId id = new SowPoId(
                        sowNumber,
                        poNumber
                );

                SowPo sowPo = sowPoRepository
                        .findById(id)
                        .orElseGet(SowPo::new);

                sowPo.setSowNumber(sowNumber);
                sowPo.setPoNumber(poNumber);

                sowPoRepository.save(sowPo);
            }
        }

        log.info("Saved {} SOW records.", sowDtos.size());
    }


//    @Transactional
//    public void importMasterData(
//            MultipartFile file,
//            MasterType masterType) throws Exception {
//
//        switch (masterType) {
//
//            case RESOURCE:
//
//                importResourceWorkbook(file);
//
//                break;
//
//            case SOW:
//
//                importSowWorkbook(file);
//
//                break;
//
//            default:
//
//                throw new IllegalArgumentException(
//                        "Unsupported Master Type : "
//                                + masterType
//                );
//        }
//    }


    /**
     * ============================================================
     * CONFIRM SOW MASTER IMPORT
     * ============================================================
     *
     * Called only after the administrator clicks Proceed
     * on the SOW Master review page.
     *
     * At this point the uploaded data has already been
     * validated and the administrator has explicitly confirmed
     * the detected changes.
     */
    @Transactional
    public void confirmSowImport(
            List<SowImportDto> sows) {

        if (sows == null || sows.isEmpty()) {

            log.warn(
                    "Confirmed SOW Master import contains no records."
            );

            return;
        }

        log.info(
                "Administrator confirmed SOW Master import. " +
                        "Persisting {} records.",
                sows.size()
        );

        /*
         * Reuse the existing persistence logic.
         *
         * saveSows() handles:
         *
         * SOW_MASTER
         * PO_MASTER
         * SOW_PO
         */
        saveSows(sows);

        log.info(
                "Confirmed SOW Master import completed successfully."
        );
    }


    @Transactional
    public ResourceImportValidationResultDto importMasterData(
            MultipartFile file,
            MasterType masterType) throws Exception {

        switch (masterType) {

            case RESOURCE:

//                return importResourceWorkbook(file);
                importResourceWorkbook(file);

            case SOW:

                /*
                 * SOW upload does not currently participate in the
                 * Employee ID validation flow.
                 *
                 * Keep the existing SOW upload behavior unchanged.
                 */
                importSowWorkbook(file);

                return ResourceImportValidationResultDto.builder()
//                        .hasMismatches(false)
                        .mismatches(List.of())
                        .build();

            default:

                throw new IllegalArgumentException(
                        "Unsupported Master Type : "
                                + masterType
                );
        }
    }

}