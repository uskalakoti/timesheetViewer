//package com.timesheet.validator.controller;
//
//import com.timesheet.validator.domain.MasterType;
//import com.timesheet.validator.dto.ImportSummaryDto;
//import com.timesheet.validator.service.MasterDataImportService;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Controller;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//
//import com.timesheet.validator.domain.MasterType;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.multipart.MultipartFile;
//import org.springframework.web.servlet.mvc.support.RedirectAttributes;
//
//@Controller
//@RequestMapping("/admin/master-data")
//@RequiredArgsConstructor
//@Slf4j
//public class MasterDataImportController {
//
//    private final MasterDataImportService masterDataImportService;
//
//    @PostMapping("/upload")
//    public String uploadMasterData(
//            @RequestParam("file")
//            MultipartFile file,
//
//            @RequestParam("masterType")
//            MasterType masterType,
//
//            RedirectAttributes redirectAttributes) {
//
//        try {
//
//            masterDataImportService.importMasterData(
//                    file,
//                    masterType
//            );
//
//            redirectAttributes.addFlashAttribute(
//                    "successMessage",
//                    masterType +
//                            " Master uploaded successfully."
//            );
//
////            ImportSummaryDto summary =
////                    masterDataImportService.importMasterData(
////                            file,
////                            masterType
////                    );
////
////            redirectAttributes.addFlashAttribute(
////                    "successMessage",
////                    summary.getMessage()
////            );
//
//        } catch (Exception ex) {
//
//            log.error("Master upload failed", ex);
//
//            redirectAttributes.addFlashAttribute(
//                    "errorMessage",
//                    ex.getMessage()
//            );
//        }
//
//        return "redirect:/admin/resources";
//    }
//
//}











//package com.timesheet.validator.controller;
//
//import com.timesheet.validator.domain.MasterType;
//import com.timesheet.validator.dto.PendingResourceImport;
//import com.timesheet.validator.dto.ResourceImportResultDto;
//import com.timesheet.validator.service.MasterDataImportService;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Controller;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//import org.springframework.web.servlet.mvc.support.RedirectAttributes;
//
//import javax.servlet.http.HttpSession;
//
//@Controller
//@RequestMapping("/admin/master-data")
//@RequiredArgsConstructor
//@Slf4j
//public class MasterDataImportController {
//
//    private static final String PENDING_RESOURCE_IMPORT =
//            "PENDING_RESOURCE_IMPORT";
//
//    private final MasterDataImportService masterDataImportService;
//
//
//    /**
//     * ============================================================
//     * UPLOAD MASTER DATA
//     * ============================================================
//     */
//    @PostMapping("/upload")
//    public String uploadMasterData(
//            @RequestParam("file") MultipartFile file,
//            @RequestParam("masterType") MasterType masterType,
//            RedirectAttributes redirectAttributes,
//            HttpSession session) {
//
//        try {
//
//            /*
//             * ========================================================
//             * RESOURCE MASTER
//             * ========================================================
//             */
//            if (masterType == MasterType.RESOURCE) {
//
//                ResourceImportResultDto result =
//                        masterDataImportService
//                                .importResourceWorkbook(file);
//
//                /*
//                 * ----------------------------------------------------
//                 * MISMATCHES FOUND
//                 * ----------------------------------------------------
//                 */
//                if (result.isRequiresConfirmation()) {
//
//                    PendingResourceImport pending =
//                            new PendingResourceImport(
//                                    result.getResources(),
//                                    result.getMismatches()
//                            );
//
//                    session.setAttribute(
//                            PENDING_RESOURCE_IMPORT,
//                            pending
//                    );
//
//                    log.warn(
//                            "Resource Master upload requires administrator confirmation."
//                    );
//
//                    return "redirect:/admin/master-data/resource-review";
//                }
//
//                /*
//                 * ----------------------------------------------------
//                 * NO MISMATCHES
//                 * ----------------------------------------------------
//                 */
//                redirectAttributes.addFlashAttribute(
//                        "successMessage",
//                        "Resource Master uploaded successfully."
//                );
//
//                return "redirect:/admin/resources";
//            }
//
//
//            /*
//             * ========================================================
//             * SOW MASTER
//             * ========================================================
//             *
//             * Existing SOW flow remains unchanged.
//             */
//            masterDataImportService.importMasterData(
//                    file,
//                    masterType
//            );
//
//            redirectAttributes.addFlashAttribute(
//                    "successMessage",
//                    masterType + " Master uploaded successfully."
//            );
//
//        } catch (Exception ex) {
//
//            log.error(
//                    "Master upload failed",
//                    ex
//            );
//
//            redirectAttributes.addFlashAttribute(
//                    "errorMessage",
//                    ex.getMessage()
//            );
//        }
//
//        return "redirect:/admin/resources";
//    }
//
//
//    /**
//     * ============================================================
//     * RESOURCE MASTER REVIEW PAGE
//     * ============================================================
//     */
////    @GetMapping("/resource-review")
////    public String resourceImportReview(
////            HttpSession session,
////            RedirectAttributes redirectAttributes) {
////
////        PendingResourceImport pending =
////                (PendingResourceImport) session.getAttribute(
////                        PENDING_RESOURCE_IMPORT
////                );
////
////        /*
////         * No pending import.
////         */
////        if (pending == null) {
////
////            redirectAttributes.addFlashAttribute(
////                    "errorMessage",
////                    "No pending Resource Master upload found."
////            );
////
////            return "redirect:/admin/resources";
////        }
////
////        return "pages/admin/resource-import-review";
////    }
//
//
//    @GetMapping("/resource-review")
//    public String resourceImportReview(
//            HttpSession session,
//            org.springframework.ui.Model model,
//            RedirectAttributes redirectAttributes) {
//
//        PendingResourceImport pending =
//                (PendingResourceImport) session.getAttribute(
//                        PENDING_RESOURCE_IMPORT
//                );
//
//        if (pending == null) {
//
//            redirectAttributes.addFlashAttribute(
//                    "errorMessage",
//                    "No pending Resource Master upload found."
//            );
//
//            return "redirect:/admin/resources";
//        }
//
//        model.addAttribute(
//                "pendingImport",
//                pending
//        );
//
//        return "pages/admin/resource-import-review";
//    }
//
//
//    /**
//     * ============================================================
//     * PROCEED WITH RESOURCE MASTER UPLOAD
//     * ============================================================
//     */
//    @PostMapping("/resource-review/proceed")
//    public String proceedResourceImport(
//            HttpSession session,
//            RedirectAttributes redirectAttributes) {
//
//        try {
//
//            PendingResourceImport pending =
//                    (PendingResourceImport) session.getAttribute(
//                            PENDING_RESOURCE_IMPORT
//                    );
//
//            if (pending == null) {
//
//                redirectAttributes.addFlashAttribute(
//                        "errorMessage",
//                        "No pending Resource Master upload found."
//                );
//
//                return "redirect:/admin/resources";
//            }
//
//            /*
//             * ----------------------------------------------------
//             * NOW PERSIST THE DATA
//             * ----------------------------------------------------
//             */
//            masterDataImportService.confirmResourceImport(
//                    pending.getResources()
//            );
//
//            /*
//             * ----------------------------------------------------
//             * REMOVE PENDING IMPORT
//             * ----------------------------------------------------
//             */
//            session.removeAttribute(
//                    PENDING_RESOURCE_IMPORT
//            );
//
//            redirectAttributes.addFlashAttribute(
//                    "successMessage",
//                    "Resource Master uploaded successfully."
//            );
//
//        } catch (Exception ex) {
//
//            log.error(
//                    "Confirmed Resource Master upload failed",
//                    ex
//            );
//
//            redirectAttributes.addFlashAttribute(
//                    "errorMessage",
//                    "Unable to upload Resource Master."
//            );
//        }
//
//        return "redirect:/admin/resources";
//    }
//
//
//    /**
//     * ============================================================
//     * CANCEL RESOURCE MASTER UPLOAD
//     * ============================================================
//     */
//    @PostMapping("/resource-review/cancel")
//    public String cancelResourceImport(
//            HttpSession session,
//            RedirectAttributes redirectAttributes) {
//
//        /*
//         * Discard pending upload.
//         *
//         * Existing Master Data remains untouched.
//         */
//        session.removeAttribute(
//                PENDING_RESOURCE_IMPORT
//        );
//
//        redirectAttributes.addFlashAttribute(
//                "successMessage",
//                "Masterdata upload cancelled. Existing records remain unchanged."
//        );
//
//        return "redirect:/admin/resources";
//    }
//}






package com.timesheet.validator.controller;

import com.timesheet.validator.domain.MasterType;
import com.timesheet.validator.dto.PendingResourceImport;
import com.timesheet.validator.dto.PendingSowImport;
import com.timesheet.validator.dto.ResourceImportResultDto;
import com.timesheet.validator.service.MasterDataImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpSession;

@Controller
@RequestMapping("/admin/master-data")
@RequiredArgsConstructor
@Slf4j
public class MasterDataImportController {

    private static final String PENDING_RESOURCE_IMPORT =
            "PENDING_RESOURCE_IMPORT";

    private static final String PENDING_SOW_IMPORT =
            "PENDING_SOW_IMPORT";

    private final MasterDataImportService masterDataImportService;


    /**
     * ============================================================
     * UPLOAD MASTER DATA
     * ============================================================
     */
    @PostMapping("/upload")
    public String uploadMasterData(
            @RequestParam("file") MultipartFile file,
            @RequestParam("masterType") MasterType masterType,
            RedirectAttributes redirectAttributes,
            HttpSession session) {

        try {

            /*
             * ========================================================
             * RESOURCE MASTER
             * ========================================================
             */
            if (masterType == MasterType.RESOURCE) {

                ResourceImportResultDto result =
                        masterDataImportService
                                .importResourceWorkbook(file);

                /*
                 * ----------------------------------------------------
                 * MISMATCHES FOUND
                 * ----------------------------------------------------
                 */
                if (result.isRequiresConfirmation()) {

                    PendingResourceImport pending =
                            new PendingResourceImport(
                                    result.getResources(),
                                    result.getMismatches()
                            );

                    session.setAttribute(
                            PENDING_RESOURCE_IMPORT,
                            pending
                    );

                    log.warn(
                            "Resource Master upload requires administrator confirmation."
                    );

                    return "redirect:/admin/master-data/resource-review";
                }

                /*
                 * ----------------------------------------------------
                 * NO MISMATCHES
                 * ----------------------------------------------------
                 */
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Resource Master uploaded successfully."
                );

                return "redirect:/admin/resources";
            }


            /*
             * ========================================================
             * SOW MASTER
             * ========================================================
             *
             * SOW Number is the primary validation key.
             *
             * importMasterData() will:
             *
             * 1. Parse the workbook
             * 2. Validate existing SOW Numbers
             * 3. Throw SowImportValidationException if mismatches
             *    are detected
             * 4. Persist automatically when there are no mismatches
             */
            if (masterType == MasterType.SOW) {

                masterDataImportService.importMasterData(
                        file,
                        masterType
                );

                /*
                 * ----------------------------------------------------
                 * NO MISMATCHES
                 * ----------------------------------------------------
                 */
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "SOW Master uploaded successfully."
                );

                return "redirect:/admin/resources";
            }


            /*
             * ========================================================
             * UNSUPPORTED MASTER TYPE
             * ========================================================
             */
            throw new IllegalArgumentException(
                    "Unsupported Master Type: " + masterType
            );


        } catch (com.timesheet.validator.service.SowImportValidationException ex) {

            /*
             * ========================================================
             * SOW VALIDATION MISMATCH
             * ========================================================
             *
             * This is NOT an upload failure.
             *
             * Store the uploaded SOW records and their validation
             * result in the HTTP session.
             *
             * Nothing has been persisted yet.
             */
            PendingSowImport pending =
                    new PendingSowImport(
                            ex.getSows(),
                            ex.getValidationResult()
                    );

            session.setAttribute(
                    PENDING_SOW_IMPORT,
                    pending
            );

            log.warn(
                    "SOW Master upload requires administrator confirmation. " +
                            "{} discrepancies detected.",
                    ex.getValidationResult()
                            .getMismatches()
                            .size()
            );

            return "redirect:/admin/master-data/sow-review";


        } catch (Exception ex) {

            /*
             * ========================================================
             * REAL UPLOAD ERROR
             * ========================================================
             */
            log.error(
                    "Master upload failed",
                    ex
            );

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/admin/resources";
    }


    /**
     * ============================================================
     * RESOURCE MASTER REVIEW PAGE
     * ============================================================
     */
    @GetMapping("/resource-review")
    public String resourceImportReview(
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes) {

        PendingResourceImport pending =
                (PendingResourceImport) session.getAttribute(
                        PENDING_RESOURCE_IMPORT
                );

        if (pending == null) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "No pending Resource Master upload found."
            );

            return "redirect:/admin/resources";
        }

        model.addAttribute(
                "pendingImport",
                pending
        );

        return "pages/admin/resource-import-review";
    }


    /**
     * ============================================================
     * PROCEED WITH RESOURCE MASTER UPLOAD
     * ============================================================
     */
    @PostMapping("/resource-review/proceed")
    public String proceedResourceImport(
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        try {

            PendingResourceImport pending =
                    (PendingResourceImport) session.getAttribute(
                            PENDING_RESOURCE_IMPORT
                    );

            if (pending == null) {

                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "No pending Resource Master upload found."
                );

                return "redirect:/admin/resources";
            }

            /*
             * ----------------------------------------------------
             * NOW PERSIST THE DATA
             * ----------------------------------------------------
             */
            masterDataImportService.confirmResourceImport(
                    pending.getResources()
            );

            /*
             * ----------------------------------------------------
             * REMOVE PENDING IMPORT
             * ----------------------------------------------------
             */
            session.removeAttribute(
                    PENDING_RESOURCE_IMPORT
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Resource Master uploaded successfully."
            );

        } catch (Exception ex) {

            log.error(
                    "Confirmed Resource Master upload failed",
                    ex
            );

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Unable to upload Resource Master."
            );
        }

        return "redirect:/admin/resources";
    }


    /**
     * ============================================================
     * CANCEL RESOURCE MASTER UPLOAD
     * ============================================================
     */
    @PostMapping("/resource-review/cancel")
    public String cancelResourceImport(
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        /*
         * Discard pending upload.
         *
         * Existing Master Data remains untouched.
         */
        session.removeAttribute(
                PENDING_RESOURCE_IMPORT
        );

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Masterdata upload cancelled. Existing records remain unchanged."
        );

        return "redirect:/admin/resources";
    }


    /**
     * ============================================================
     * SOW MASTER REVIEW PAGE
     * ============================================================
     */
    @GetMapping("/sow-review")
    public String sowImportReview(
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes) {

        PendingSowImport pending =
                (PendingSowImport) session.getAttribute(
                        PENDING_SOW_IMPORT
                );

        /*
         * ----------------------------------------------------
         * No pending SOW upload
         * ----------------------------------------------------
         */
        if (pending == null) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "No pending SOW Master upload found."
            );

            return "redirect:/admin/resources";
        }

        /*
         * ----------------------------------------------------
         * Send pending upload to review page
         * ----------------------------------------------------
         */
        model.addAttribute(
                "pendingImport",
                pending
        );

        return "pages/admin/sow-import-review";
    }


    /**
     * ============================================================
     * PROCEED WITH SOW MASTER UPLOAD
     * ============================================================
     *
     * IMPORTANT:
     *
     * The actual persistence will happen only here.
     *
     * This means:
     *
     * Upload
     *    ↓
     * Validation
     *    ↓
     * Review
     *    ↓
     * Proceed
     *    ↓
     * saveSows()
     */
    @PostMapping("/sow-review/proceed")
    public String proceedSowImport(
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        try {

            PendingSowImport pending =
                    (PendingSowImport) session.getAttribute(
                            PENDING_SOW_IMPORT
                    );

            /*
             * ----------------------------------------------------
             * No pending upload
             * ----------------------------------------------------
             */
            if (pending == null) {

                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "No pending SOW Master upload found."
                );

                return "redirect:/admin/resources";
            }


            /*
             * ----------------------------------------------------
             * PERSIST THE CONFIRMED DATA
             * ----------------------------------------------------
             *
             * We will implement confirmSowImport()
             * in MasterDataImportService.
             */
            masterDataImportService.confirmSowImport(
                    pending.getSows()
            );


            /*
             * ----------------------------------------------------
             * REMOVE PENDING UPLOAD
             * ----------------------------------------------------
             */
            session.removeAttribute(
                    PENDING_SOW_IMPORT
            );


            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "SOW Master updated successfully."
            );


        } catch (Exception ex) {

            log.error(
                    "Confirmed SOW Master upload failed",
                    ex
            );

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Unable to update SOW Master."
            );
        }


        return "redirect:/admin/resources";
    }


    /**
     * ============================================================
     * CANCEL SOW MASTER UPLOAD
     * ============================================================
     */
    @PostMapping("/sow-review/cancel")
    public String cancelSowImport(
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        /*
         * ----------------------------------------------------
         * Discard pending SOW upload.
         *
         * Existing SOW Master data remains unchanged.
         * ----------------------------------------------------
         */
        session.removeAttribute(
                PENDING_SOW_IMPORT
        );


        log.info(
                "SOW Master upload cancelled by administrator."
        );


        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Upload cancelled. Existing SOW Master records remain unchanged."
        );


        return "redirect:/admin/resources";
    }
}










