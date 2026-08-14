package com.salesmanager.crm.quotation;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns quotation_number_counters' lock/increment logic in isolation - identical shape and
 * locking discipline to invoicing.InvoiceNumberService (see that class's javadoc for the full
 * reasoning on why this is a plain SELECT...FOR UPDATE inside the caller's existing transaction,
 * not AdvisoryLockRunner). Kept as its own counter table/service rather than sharing
 * invoice_number_counters, since Quotation and Invoice are independently-numbered document
 * series (QT-... vs INV-...).
 */
@Service
public class QuotationNumberService {

    private final EntityManager entityManager;

    public QuotationNumberService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public String allocateNext(UUID organizationId, int year) {
        entityManager.createNativeQuery(
                        "INSERT INTO quotation_number_counters (organization_id, year, next_number) "
                                + "VALUES (:orgId, :year, 1) ON CONFLICT (organization_id, year) DO NOTHING")
                .setParameter("orgId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        Number nextNumber = (Number) entityManager.createNativeQuery(
                        "SELECT next_number FROM quotation_number_counters "
                                + "WHERE organization_id = :orgId AND year = :year FOR UPDATE")
                .setParameter("orgId", organizationId)
                .setParameter("year", year)
                .getSingleResult();

        entityManager.createNativeQuery(
                        "UPDATE quotation_number_counters SET next_number = next_number + 1 "
                                + "WHERE organization_id = :orgId AND year = :year")
                .setParameter("orgId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        return String.format("QT-%d-%04d", year, nextNumber.intValue());
    }
}
