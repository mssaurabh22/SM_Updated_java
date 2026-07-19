package com.salesmanager.crm.lead;

import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic WHERE clauses for GET /leads' optional status/ownerId/interestLevelId
 * filters, composed via Specification.where(...).and(...) in LeadService#list rather than a
 * combinatorial pile of derived query methods (findByStatus, findByOwnerId,
 * findByStatusAndOwnerId, ...). Each method returns null for a null filter value, which
 * Specification.and(...) treats as "no additional restriction" (a standard Spring Data
 * Specification idiom).
 */
final class LeadSpecifications {

    private LeadSpecifications() {
    }

    static Specification<Lead> hasStatus(LeadStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Lead> hasOwner(UUID ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }

    static Specification<Lead> hasInterestLevel(UUID interestLevelId) {
        return (root, query, cb) ->
                interestLevelId == null ? null : cb.equal(root.get("interestLevelId"), interestLevelId);
    }
}
