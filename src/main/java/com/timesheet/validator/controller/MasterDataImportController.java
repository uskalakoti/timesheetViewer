package com.timesheet.validator.controller;

import com.timesheet.validator.domain.MasterType;
import com.timesheet.validator.dto.ImportSummaryDto;
import com.timesheet.validator.service.MasterDataImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.timesheet.validator.domain.MasterType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/master-data")
@RequiredArgsConstructor
@Slf4j
public class MasterDataImportController {

    private final MasterDataImportService masterDataImportService;

    @PostMapping("/upload")
    public String uploadMasterData(
            @RequestParam("file")
            MultipartFile file,

            @RequestParam("masterType")
            MasterType masterType,

            RedirectAttributes redirectAttributes) {

        try {

            masterDataImportService.importMasterData(
                    file,
                    masterType
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    masterType +
                            " Master uploaded successfully."
            );

//            ImportSummaryDto summary =
//                    masterDataImportService.importMasterData(
//                            file,
//                            masterType
//                    );
//
//            redirectAttributes.addFlashAttribute(
//                    "successMessage",
//                    summary.getMessage()
//            );

        } catch (Exception ex) {

            log.error("Master upload failed", ex);

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/admin/resources";
    }

}