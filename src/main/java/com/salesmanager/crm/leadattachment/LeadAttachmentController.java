package com.salesmanager.crm.leadattachment;

import com.salesmanager.crm.leadattachment.dto.LeadAttachmentResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Thin controller - all validation/storage/visibility-scoping logic lives in
 * LeadAttachmentService, same layering as every other feature controller in this codebase. No
 * @PreAuthorize role restriction: visibility is enforced per-lead by LeadAttachmentService
 * delegating to LeadService#getById, same as the Lead endpoints themselves (both ADMIN and
 * EMPLOYEE can attach/view files on a lead they can already see).
 */
@RestController
public class LeadAttachmentController {

    private final LeadAttachmentService leadAttachmentService;

    public LeadAttachmentController(LeadAttachmentService leadAttachmentService) {
        this.leadAttachmentService = leadAttachmentService;
    }

    @PostMapping(value = "/leads/{leadId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LeadAttachmentResponse upload(@PathVariable UUID leadId,
                                          @RequestPart("file") MultipartFile file) {
        return LeadAttachmentResponse.from(leadAttachmentService.upload(leadId, file));
    }

    @GetMapping("/leads/{leadId}/attachments")
    public List<LeadAttachmentResponse> list(@PathVariable UUID leadId) {
        return leadAttachmentService.list(leadId).stream()
                .map(LeadAttachmentResponse::from)
                .toList();
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID attachmentId) {
        LeadAttachmentService.LoadedAttachment loaded = leadAttachmentService.download(attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeHeaderValue(loaded.fileName()) + "\"")
                .contentType(resolveMediaType(loaded.contentType()))
                .body(loaded.content());
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID attachmentId) {
        leadAttachmentService.delete(attachmentId);
    }

    private static MediaType resolveMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** Strips characters that would break the Content-Disposition header's quoted-string
     * syntax or otherwise be unsafe in a header value (quotes, CR/LF) - only guards the header,
     * the downloaded byte content itself is unaffected. */
    private static String sanitizeHeaderValue(String fileName) {
        return fileName.replaceAll("[\"\\r\\n]", "_");
    }
}
