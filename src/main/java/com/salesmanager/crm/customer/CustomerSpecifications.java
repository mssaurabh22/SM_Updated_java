package com.salesmanager.crm.customer;

import jakarta.persistence.criteria.Predicate;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic WHERE clauses for GET /customers' optional search/city/state/active filters,
 * same Specification.where(...).and(...) idiom as LeadSpecifications - each method returns null
 * for a null filter value, which Specification.and(...) treats as "no additional restriction".
 */
final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    static Specification<Customer> isActive(boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    static Specification<Customer> hasCity(UUID cityId) {
        return (root, query, cb) -> cityId == null ? null : cb.equal(root.get("cityId"), cityId);
    }

    static Specification<Customer> hasState(UUID stateId) {
        return (root, query, cb) -> stateId == null ? null : cb.equal(root.get("stateId"), stateId);
    }

    /** Case-insensitive substring match across name/contactPerson/phone/email/gstin - the
     * customer-picker's search field. */
    static Specification<Customer> matchesSearch(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String likePattern = "%" + term.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate nameMatch = cb.like(cb.lower(root.get("name")), likePattern);
            Predicate contactMatch = cb.and(cb.isNotNull(root.get("contactPerson")),
                    cb.like(cb.lower(root.get("contactPerson")), likePattern));
            Predicate phoneMatch = cb.and(cb.isNotNull(root.get("phone")),
                    cb.like(cb.lower(root.get("phone")), likePattern));
            Predicate emailMatch = cb.and(cb.isNotNull(root.get("email")),
                    cb.like(cb.lower(root.get("email")), likePattern));
            Predicate gstinMatch = cb.and(cb.isNotNull(root.get("gstin")),
                    cb.like(cb.lower(root.get("gstin")), likePattern));
            return cb.or(nameMatch, contactMatch, phoneMatch, emailMatch, gstinMatch);
        };
    }
}
