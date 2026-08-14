package com.salesmanager.crm.quotation;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.leadattachment.AttachmentStorageService;
import com.salesmanager.crm.leadattachment.InvalidAttachmentException;
import com.salesmanager.crm.security.CurrentUser;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates Quotation attachment upload/list/download/delete - same shape as
 * leadattachment.LeadAttachmentService, reusing its AttachmentStorageService bean directly
 * (dependency-injected, not duplicated). Delegates visibility to QuotationService#getById
 * rather than re-implementing owner/team-scoping here, same information-hiding rule.
 */
@Service
public class QuotationAttachmentService {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain");

    private final QuotationAttachmentRepository quotationAttachmentRepository;
    private final AttachmentStorageService storageService;
    private final QuotationService quotationService;
    private final CurrentUser currentUser;

    public QuotationAttachmentService(QuotationAttachmentRepository quotationAttachmentRepository,
                                       AttachmentStorageService storageService,
                                       QuotationService quotationService,
                                       CurrentUser currentUser) {
        this.quotationAttachmentRepository = quotationAttachmentRepository;
        this.storageService = storageService;
        this.quotationService = quotationService;
        this.currentUser = currentUser;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidAttachmentException.class})
    public QuotationAttachment upload(UUID quotationId, MultipartFile file) {
        quotationService.getById(quotationId);
        validate(file);

        UUID organizationId = currentUser.get().getOrganizationId();
        String storageKey;
        try {
            storageKey = storageService.store(organizationId, quotationId, file.getOriginalFilename(),
                    file.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store quotation attachment", e);
        }

        QuotationAttachment attachment = QuotationAttachment.builder()
                .quotationId(quotationId)
                .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment")
                .storageKey(storageKey)
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .uploadedBy(currentUser.get().getEmployeeId())
                .build();
        return quotationAttachmentRepository.saveAndFlush(attachment);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public List<QuotationAttachment> list(UUID quotationId) {
        quotationService.getById(quotationId);
        return quotationAttachmentRepository.findByQuotationIdOrderByCreatedAtDesc(quotationId);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public LoadedAttachment download(UUID attachmentId) {
        QuotationAttachment attachment = quotationAttachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
        quotationService.getById(attachment.getQuotationId());
        try {
            byte[] content = storageService.load(attachment.getStorageKey());
            return new LoadedAttachment(attachment.getFileName(), attachment.getContentType(), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load quotation attachment", e);
        }
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public void delete(UUID attachmentId) {
        QuotationAttachment attachment = quotationAttachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
        quotationService.getById(attachment.getQuotationId());
        quotationAttachmentRepository.delete(attachment);
        try {
            storageService.delete(attachment.getStorageKey());
        } catch (IOException e) {
            // Best-effort - see LeadAttachmentService#delete's identical comment.
        }
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidAttachmentException("Attachment file is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidAttachmentException("Attachment exceeds the 10MB size limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidAttachmentException(
                    "Unsupported attachment type: " + contentType
                            + " - allowed types are images, PDF, Word/Excel documents, and plain text");
        }
    }

    public record LoadedAttachment(String fileName, String contentType, byte[] content) {
    }
}
