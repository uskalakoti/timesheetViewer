package com.timesheet.validator.service;

import com.timesheet.validator.domain.*;
import com.timesheet.validator.dto.ResourceImportDto;
import com.timesheet.validator.repository.ResourceRepository;
import com.timesheet.validator.dto.SowImportDto;
import com.timesheet.validator.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import com.timesheet.validator.dto.ImportSummaryDto;

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

    /**
     * Imports Resource Master workbook.
     */
    public List<ResourceImportDto> importResourceWorkbook(
            MultipartFile workbook) throws Exception {

        log.info("Importing Resource workbook : {}",
                workbook.getOriginalFilename());

//        return excelParserService.parseResourceWorkbook(workbook);

        List<ResourceImportDto> resources =
                excelParserService.parseResourceWorkbook(workbook);

        saveResources(resources);

        return resources;
    }

    /**
     * Imports SOW Master workbook.
     */
    public List<SowImportDto> importSowWorkbook(
            MultipartFile workbook) throws Exception {

        log.info("Importing SOW workbook : {}",
                workbook.getOriginalFilename());

//        return excelParserService.parseSowWorkbook(workbook);

        List<SowImportDto> sows =
                excelParserService.parseSowWorkbook(workbook);

        saveSows(sows);

        return sows;
    }

    @Transactional
    private void saveResources(List<ResourceImportDto> resources) {

        for (ResourceImportDto dto : resources) {

            /*
             * ------------------------------------------------------------
             * RESOURCE
             * ------------------------------------------------------------
             * Business Key = RESOURCE_ID (Employee ID)
             */
            Resource resource = resourceRepository
                    .findByResourceId(dto.getResourceId())
                    .orElseGet(Resource::new);

            resource.setResourceId(dto.getResourceId());
            resource.setName(dto.getEmployeeName());

            resource.setLocation(dto.getLocation());
            resource.setCompany(dto.getCompany());

            resource.setCompany(
                    dto.getCompany() != null && !dto.getCompany().isBlank()
                            ? dto.getCompany()
                            : "Atain"
            );

            resource.setWorkingHoursPerDay(8.0);

            resourceRepository.save(resource);

            /*
             * ------------------------------------------------------------
             * RESOURCE_SOW
             * ------------------------------------------------------------
             * Business Key =
             * RESOURCE_ID + SOW_NUMBER
             */
            ResourceSowId id = new ResourceSowId(
                    dto.getResourceId(),
                    dto.getSowNumber()
            );

            ResourceSow resourceSow = resourceSowRepository
                    .findById(id)
                    .orElseGet(ResourceSow::new);

            resourceSow.setResourceId(dto.getResourceId());

            resourceSow.setSowNumber(dto.getSowNumber());

            /*
             * Designation from Resource Master workbook
             * is stored as the role for this Resource-SOW relationship.
             */
            resourceSow.setRoleInSow(dto.getRoleInSow());

            /*
             * Will remain null until imported
             * from the correct master source.
             */
            resourceSow.setAssignedTeam(dto.getAssignedTeam());

            resourceSow.setProjectCode(dto.getProjectCode());

            resourceSow.setSubProject(dto.getSubProject());

            resourceSow.setTravelExpense(dto.getTravelExpense());

            resourceSowRepository.save(resourceSow);
        }

        log.info("Saved {} Resource records.", resources.size());
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


    @Transactional
    public void importMasterData(
            MultipartFile file,
            MasterType masterType) throws Exception {

        switch (masterType) {

            case RESOURCE:

                importResourceWorkbook(file);

                break;

            case SOW:

                importSowWorkbook(file);

                break;

            default:

                throw new IllegalArgumentException(
                        "Unsupported Master Type : "
                                + masterType
                );
        }
    }

}