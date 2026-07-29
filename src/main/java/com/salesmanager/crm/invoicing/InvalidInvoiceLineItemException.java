package com.salesmanager.crm.invoicing;

import lombok.Getter;

/**
 * Thrown when a line item is malformed - neither or both of productId/description+unitPrice
 * set, a non-positive quantity, or a productId that doesn't resolve to an active, in-tenant
 * Product. Mapped to 400 Bad Request with a field-level message by GlobalExceptionHandler.
 */
@Getter
public class InvalidInvoiceLineItemException extends RuntimeException {

    private final String field;

    public InvalidInvoiceLineItemException(String field, String message) {
        super(message);
        this.field = field;
    }
}
