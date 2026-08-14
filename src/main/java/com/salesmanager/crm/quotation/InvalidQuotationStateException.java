package com.salesmanager.crm.quotation;

/** Thrown when a status transition or edit is attempted that the quotation's current status
 * doesn't allow (e.g. editing an APPROVED quotation, converting a DRAFT one, converting twice).
 * Mapped to 409 Conflict by GlobalExceptionHandler - a business-state conflict, not a malformed
 * payload. */
public class InvalidQuotationStateException extends RuntimeException {

    public InvalidQuotationStateException(String message) {
        super(message);
    }
}
