package com.salesmanager.crm.inventory;

import lombok.Getter;

/** Thrown for a malformed stock adjustment (e.g. a zero quantityChange). Mapped to 400 Bad
 * Request with a field-level message by GlobalExceptionHandler. */
@Getter
public class InvalidStockAdjustmentException extends RuntimeException {

    private final String field;

    public InvalidStockAdjustmentException(String field, String message) {
        super(message);
        this.field = field;
    }
}
