package com.salesmanager.crm.invoicing;

import lombok.Getter;

/** Thrown for a rejected logo upload (wrong content type, too large, or empty file). Mapped
 * to 400 Bad Request with a field-level message by GlobalExceptionHandler - same shape as
 * inventory.InvalidStockAdjustmentException. */
@Getter
public class InvalidLogoException extends RuntimeException {

    private final String field;

    public InvalidLogoException(String field, String message) {
        super(message);
        this.field = field;
    }
}
