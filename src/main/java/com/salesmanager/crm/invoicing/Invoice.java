package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * Create-only in v1 - no edit/delete/void endpoint exists, so this entity is written once by
 * InvoiceService#create and never mutated again except {@link #getStatus()} (mark paid/unpaid).
 *
 * <p>{@code ownerId}/{@code createdBy} mirror lead.Lead's exact two-column shape: ownerId is
 * who this invoice is visible to under the EMPLOYEE-role/TEAM_VISIBILITY scoping rule,
 * createdBy is pure audit - always equal today (no reassignment endpoint exists), kept as two
 * columns for consistency with Lead's convention.
 *
 * <p>{@code leadId} is optional - an invoice can stand alone with no CRM Lead at all. When a
 * Lead IS picked to prefill, the customer_* fields below are SNAPSHOTTED at creation time, not
 * live-joined - editing the source Lead afterwards never changes an already-created invoice,
 * same "denormalize at write time" discipline as activity.ActivityLog.
 */
@Entity
@Table(name = "invoices")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Invoice extends TenantAware {

    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "customer_name", nullable = false, length = 255)
    private String customerName;

    @Column(name = "customer_contact_person", length = 255)
    private String customerContactPerson;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "customer_address", length = 1000)
    private String customerAddress;

    @Column(name = "customer_gstin", length = 20)
    private String customerGstin;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.UNPAID;

    @Column(length = 2000)
    private String notes;
}
