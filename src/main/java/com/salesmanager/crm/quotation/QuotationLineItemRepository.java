package com.salesmanager.crm.quotation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotationLineItemRepository extends JpaRepository<QuotationLineItem, UUID> {

    List<QuotationLineItem> findByQuotationIdOrderBySortOrderAsc(UUID quotationId);

    void deleteByQuotationId(UUID quotationId);
}
