package com.salesmanager.crm.invoicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here. JpaSpecificationExecutor backs InvoiceService#list's
 * dynamic owner/status filter combinations (see InvoiceSpecifications), same shape as
 * LeadRepository.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    boolean existsByInvoiceNumber(String invoiceNumber);

    /** Backs ReportingService#revenue (Dashboard's "Revenue (YTD)" stat) - COALESCE guards the
     * no-invoices-yet case, since SUM() over zero rows is SQL NULL, not zero. */
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i "
            + "WHERE i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo")
    BigDecimal sumGrandTotalBetween(@Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /** Team-visibility counterpart to {@link #sumGrandTotalBetween(LocalDate, LocalDate)}. */
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i "
            + "WHERE i.ownerId IN :ownerIds AND i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo")
    BigDecimal sumGrandTotalForOwnersBetween(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    // --- Everything below backs ReportingService#quotationInvoiceSummary's stat cards. ---

    long countByStatusAndInvoiceDateBetween(InvoiceStatus status, LocalDate dateFrom, LocalDate dateTo);

    long countByOwnerIdInAndStatusAndInvoiceDateBetween(Set<UUID> ownerIds, InvoiceStatus status,
                                                          LocalDate dateFrom, LocalDate dateTo);

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i "
            + "WHERE i.status = :status AND i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo")
    BigDecimal sumGrandTotalByStatusBetween(@Param("status") InvoiceStatus status,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i "
            + "WHERE i.ownerId IN :ownerIds AND i.status = :status "
            + "AND i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo")
    BigDecimal sumGrandTotalByStatusForOwnersBetween(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("status") InvoiceStatus status, @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);

    /** "Pending Payments" = unpaid AND already past its due date (overdue), all-time - distinct
     * from "Pending Invoices" above (unpaid, created within the selected period regardless of
     * due date). */
    long countByStatusAndDueDateLessThan(InvoiceStatus status, LocalDate date);

    long countByOwnerIdInAndStatusAndDueDateLessThan(Set<UUID> ownerIds, InvoiceStatus status, LocalDate date);

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.status = :status AND i.dueDate < :date")
    BigDecimal sumGrandTotalByStatusAndDueDateBefore(@Param("status") InvoiceStatus status,
            @Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i "
            + "WHERE i.ownerId IN :ownerIds AND i.status = :status AND i.dueDate < :date")
    BigDecimal sumGrandTotalByStatusAndDueDateBeforeForOwners(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("status") InvoiceStatus status, @Param("date") LocalDate date);

    /** "Outstanding" = every unpaid invoice ever, not scoped to a period - total receivable. */
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.status = :status")
    BigDecimal sumGrandTotalByStatus(@Param("status") InvoiceStatus status);

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.ownerId IN :ownerIds AND i.status = :status")
    BigDecimal sumGrandTotalByStatusForOwners(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("status") InvoiceStatus status);
}
