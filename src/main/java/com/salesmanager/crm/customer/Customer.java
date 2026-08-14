package com.salesmanager.crm.customer;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * A real, reusable customer master - deliberately new (not derived from Lead): Quotations and
 * Invoices need a "pick or quick-add a customer" flow independent of the sales pipeline, and a
 * Customer may exist with no corresponding Lead at all (e.g. a repeat buyer). Org-wide, unowned
 * (no ownerId) - same visibility shape as Product/master data, not owner-scoped like Lead.
 *
 * Quotation/Invoice snapshot these fields onto their own row at creation time rather than
 * live-joining - editing a Customer afterward never retroactively changes an already-created
 * Quotation/Invoice, same "keeps its own copy from this point on" discipline the invoicing
 * module already applies to its optional Lead link.
 */
@Entity
@Table(name = "customers")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Customer extends TenantAware {

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "contact_person", length = 255)
    private String contactPerson;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(length = 1000)
    private String address;

    @Column(name = "city_id")
    private UUID cityId;

    @Column(name = "state_id")
    private UUID stateId;

    @Column(name = "industry_id")
    private UUID industryId;

    @Column(length = 20)
    private String gstin;

    @Column(length = 2000)
    private String notes;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;
}
