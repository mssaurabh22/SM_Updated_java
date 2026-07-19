package com.salesmanager.crm.activity;

import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic WHERE clauses for GET /activity's optional leadId/ownerId/type filters,
 * composed via Specification.where(...).and(...) in ActivityLogService#list, same style as
 * LeadSpecifications/VisitSpecifications. Each method returns null for a null filter value,
 * which Specification.and(...) treats as "no additional restriction".
 */
final class ActivityLogSpecifications {

    private ActivityLogSpecifications() {
    }

    static Specification<ActivityLog> hasLeadId(UUID leadId) {
        return (root, query, cb) -> leadId == null ? null : cb.equal(root.get("leadId"), leadId);
    }

    static Specification<ActivityLog> hasOwnerId(UUID ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }

    static Specification<ActivityLog> hasType(ActivityType type) {
        return (root, query, cb) -> type == null ? null : cb.equal(root.get("type"), type);
    }
}
