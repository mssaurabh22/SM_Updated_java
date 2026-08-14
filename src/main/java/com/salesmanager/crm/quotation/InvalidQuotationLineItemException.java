package com.salesmanager.crm.quotation;

import lombok.Getter;

/** Thrown when a line item is malformed - neither or both of productId/description+unitPrice
 * set, or a non-positive quantity. Mapped to 400 Bad Request by GlobalExceptionHandler. */
@Getter
public class InvalidQuotationLineItemException extends RuntimeException {

    private final String field;

    public InvalidQuotationLineItemException(String field, String message) {
        super(message);
        this.field = field;
    }
}
