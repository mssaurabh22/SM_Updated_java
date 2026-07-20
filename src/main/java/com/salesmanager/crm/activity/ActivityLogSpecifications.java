package com.salesmanager.crm.activity;

import java.util.Set;
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

    /** Team-visibility scoping (FeatureEntitlement.TEAM_VISIBILITY) - owner in a manager's scope. */
    static Specification<ActivityLog> hasOwnerIdIn(Set<UUID> ownerIds) {
        return (root, query, cb) -> ownerIds == null ? null : root.get("ownerId").in(ownerIds);
    }

    static Specification<ActivityLog> hasType(ActivityType type) {
        return (root, query, cb) -> type == null ? null : cb.equal(root.get("type"), type);
    }
}
