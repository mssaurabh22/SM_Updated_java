package com.salesmanager.crm.visit;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic WHERE clauses for GET /visits' optional leadId/status/date-range filters,
 * composed via Specification.where(...).and(...) in VisitService#list, same style as
 * LeadSpecifications. Each method returns null for a null filter value, which
 * Specification.and(...) treats as "no additional restriction".
 */
final class VisitSpecifications {

    private VisitSpecifications() {
    }

    static Specification<Visit> hasLeadId(UUID leadId) {
        return (root, query, cb) -> leadId == null ? null : cb.equal(root.get("leadId"), leadId);
    }

    static Specification<Visit> hasStatus(VisitStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Visit> visitDateFrom(LocalDate dateFrom) {
        return (root, query, cb) -> dateFrom == null ? null : cb.greaterThanOrEqualTo(root.get("visitDate"), dateFrom);
    }

    static Specification<Visit> visitDateTo(LocalDate dateTo) {
        return (root, query, cb) -> dateTo == null ? null : cb.lessThanOrEqualTo(root.get("visitDate"), dateTo);
    }

    /**
     * Used to force an EMPLOYEE's visibility down to only visits whose parent lead they own
     * (VisitService#list). An empty collection correctly yields "no rows" (JPA's {@code IN}
     * predicate over an empty collection is always false), not an unrestricted match.
     */
    static Specification<Visit> hasLeadIdIn(Collection<UUID> leadIds) {
        return (root, query, cb) -> root.get("leadId").in(leadIds);
    }
}
