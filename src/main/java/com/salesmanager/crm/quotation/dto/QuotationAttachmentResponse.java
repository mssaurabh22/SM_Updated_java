package com.salesmanager.crm.quotation.dto;

import com.salesmanager.crm.quotation.QuotationAttachment;
import java.time.OffsetDateTime;
import java.util.UUID;

public record QuotationAttachmentResponse(
        UUID id,
        UUID quotationId,
        String fileName,
        String contentType,
        long fileSize,
        UUID uploadedBy,
        OffsetDateTime createdAt) {

    public static QuotationAttachmentResponse from(QuotationAttachment attachment) {
        return new QuotationAttachmentResponse(
                attachment.getId(),
                attachment.getQuotationId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getFileSize(),
                attachment.getUploadedBy(),
                attachment.getCreatedAt());
    }
}
