package com.salesmanager.crm.leadimport;

import com.salesmanager.crm.leadimport.dto.LeadImportCommitRequest;
import com.salesmanager.crm.leadimport.dto.LeadImportPreviewResponse;
import com.salesmanager.crm.leadimport.dto.LeadImportResultResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Thin controller - all parsing/mapping/resolution/duplicate-check/creation logic lives in
 * LeadImportService, same layering as every other feature controller in this codebase. Both
 * endpoints are ADMIN-only: bulk-importing hundreds of historical Leads at once is a bulk
 * data-mutation/oversight action, matching this app's existing pattern for reassignment/master
 * data management/reporting - not something an individual EMPLOYEE does for themselves.
 *
 * Both endpoints accept the SAME uploaded file (re-submitted on commit, not cached
 * server-side between the two calls) - see LeadImportService's class javadoc for why.
 */
@RestController
@RequestMapping("/leads/import")
public class LeadImportController {

    private final LeadImportService leadImportService;

    public LeadImportController(LeadImportService leadImportService) {
        this.leadImportService = leadImportService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public LeadImportPreviewResponse preview(@RequestPart("file") MultipartFile file) {
        return leadImportService.preview(file);
    }

    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public LeadImportResultResponse commit(@RequestPart("file") MultipartFile file,
                                            @Valid @RequestPart("request") LeadImportCommitRequest request) {
        return leadImportService.commit(file, request);
    }
}
