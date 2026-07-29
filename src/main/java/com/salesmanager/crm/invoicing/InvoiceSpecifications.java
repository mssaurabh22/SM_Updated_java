package com.salesmanager.crm.invoicing;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic WHERE clauses for GET /invoices' owner-scoping - same shape as
 * lead.LeadSpecifications (hasOwner/hasOwnerIn), reused here rather than imported directly
 * since it's tied to the Lead entity type, not a generically reusable Specification.
 */
final class InvoiceSpecifications {

    private InvoiceSpecifications() {
    }

    static Specification<Invoice> hasOwner(UUID ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }

    static Specification<Invoice> hasOwnerIn(Set<UUID> ownerIds) {
        return (root, query, cb) -> ownerIds == null ? null : root.get("ownerId").in(ownerIds);
    }

    static Specification<Invoice> hasStatus(InvoiceStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }
}
