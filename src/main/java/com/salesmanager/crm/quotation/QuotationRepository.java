package com.salesmanager.crm.quotation;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface QuotationRepository extends JpaRepository<Quotation, UUID>, JpaSpecificationExecutor<Quotation> {

    long countByStatus(QuotationStatus status);

    /** Backs ReportingService#quotationInvoiceSummary's "Total Quotations"/"Approved
     * Quotations"/"Converted to Invoice" stat cards. */
    long countByQuotationDateBetween(LocalDate dateFrom, LocalDate dateTo);

    long countByOwnerIdInAndQuotationDateBetween(Set<UUID> ownerIds, LocalDate dateFrom, LocalDate dateTo);

    long countByStatusAndQuotationDateBetween(QuotationStatus status, LocalDate dateFrom, LocalDate dateTo);

    long countByOwnerIdInAndStatusAndQuotationDateBetween(Set<UUID> ownerIds, QuotationStatus status,
                                                           LocalDate dateFrom, LocalDate dateTo);
}
