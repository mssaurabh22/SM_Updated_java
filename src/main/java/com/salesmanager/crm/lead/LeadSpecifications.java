package com.salesmanager.crm.lead;

import jakarta.persistence.criteria.Predicate;
import java.util.Set;
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

    /** Team-visibility scoping (FeatureEntitlement.TEAM_VISIBILITY) - owner in a manager's scope. */
    static Specification<Lead> hasOwnerIn(Set<UUID> ownerIds) {
        return (root, query, cb) -> ownerIds == null ? null : root.get("ownerId").in(ownerIds);
    }

    static Specification<Lead> hasInterestLevel(UUID interestLevelId) {
        return (root, query, cb) ->
                interestLevelId == null ? null : cb.equal(root.get("interestLevelId"), interestLevelId);
    }

    /** Case-insensitive substring match across companyName/contactPerson/contactNo/email - for
     * the "find an existing lead" picker (see LeadFilter#search's javadoc). email is nullable,
     * so its LIKE predicate is guarded with an IS NOT NULL check first (Postgres LOWER(NULL)
     * evaluates to NULL, which a LIKE comparison never matches, but being explicit here avoids
     * relying on that implicitly). */
    static Specification<Lead> matchesSearch(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String likePattern = "%" + term.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate companyMatch = cb.like(cb.lower(root.get("companyName")), likePattern);
            Predicate contactMatch = cb.like(cb.lower(root.get("contactPerson")), likePattern);
            Predicate phoneMatch = cb.like(cb.lower(root.get("contactNo")), likePattern);
            Predicate emailMatch = cb.and(
                    cb.isNotNull(root.get("email")),
                    cb.like(cb.lower(root.get("email")), likePattern));
            return cb.or(companyMatch, contactMatch, phoneMatch, emailMatch);
        };
    }
}
