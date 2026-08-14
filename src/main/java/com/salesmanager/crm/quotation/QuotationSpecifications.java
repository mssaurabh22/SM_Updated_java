package com.salesmanager.crm.quotation;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Same Specification.where(...).and(...) idiom as lead.LeadSpecifications/
 * invoicing.InvoiceSpecifications - each method returns null for a null filter value. */
final class QuotationSpecifications {

    private QuotationSpecifications() {
    }

    static Specification<Quotation> hasOwner(UUID ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }

    static Specification<Quotation> hasOwnerIn(Set<UUID> ownerIds) {
        return (root, query, cb) -> ownerIds == null ? null : root.get("ownerId").in(ownerIds);
    }

    static Specification<Quotation> hasStatus(QuotationStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Quotation> hasCustomer(UUID customerId) {
        return (root, query, cb) -> customerId == null ? null : cb.equal(root.get("customerId"), customerId);
    }

    static Specification<Quotation> hasCity(UUID cityId) {
        return (root, query, cb) -> cityId == null ? null : cb.equal(root.get("cityId"), cityId);
    }

    static Specification<Quotation> hasState(UUID stateId) {
        return (root, query, cb) -> stateId == null ? null : cb.equal(root.get("stateId"), stateId);
    }

    static Specification<Quotation> quotationDateBetween(LocalDate dateFrom, LocalDate dateTo) {
        return (root, query, cb) -> {
            Predicate fromPredicate = dateFrom == null ? null
                    : cb.greaterThanOrEqualTo(root.get("quotationDate"), dateFrom);
            Predicate toPredicate = dateTo == null ? null
                    : cb.lessThanOrEqualTo(root.get("quotationDate"), dateTo);
            if (fromPredicate == null) return toPredicate;
            if (toPredicate == null) return fromPredicate;
            return cb.and(fromPredicate, toPredicate);
        };
    }

    static Specification<Quotation> matchesSearch(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String likePattern = "%" + term.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("customerName")), likePattern),
                cb.like(cb.lower(root.get("quotationNumber")), likePattern));
    }
}
