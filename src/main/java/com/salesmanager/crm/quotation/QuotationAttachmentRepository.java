package com.salesmanager.crm.quotation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotationAttachmentRepository extends JpaRepository<QuotationAttachment, UUID> {

    List<QuotationAttachment> findByQuotationIdOrderByCreatedAtDesc(UUID quotationId);
}
