package com.salesmanager.crm.quotation;

/**
 * DRAFT -> SENT -> (APPROVED | REJECTED) -> CONVERTED (once turned into a real Invoice via
 * QuotationController#convertToInvoice). CONVERTED is terminal - see QuotationService for the
 * exact transition rules enforced server-side.
 */
public enum QuotationStatus {
    DRAFT,
    SENT,
    APPROVED,
    REJECTED,
    CONVERTED
}
