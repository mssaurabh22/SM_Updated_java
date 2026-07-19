package com.salesmanager.crm.theme;

import lombok.Getter;

/**
 * Thrown by {@link ThemeService} when {@code mode}/{@code density} (whose allowed values
 * aren't expressible as a simple {@code @Pattern}-friendly regex the way {@code primaryColor}
 * is) don't match one of their fixed allowed values. Mapped to 400 Bad Request with a
 * field-level message by GlobalExceptionHandler, same ErrorResponse.FieldErrorDetail shape
 * as Bean Validation failures and masterdata.InvalidReferenceException.
 */
@Getter
public class InvalidThemeException extends RuntimeException {

    private final String field;

    public InvalidThemeException(String field, String message) {
        super(message);
        this.field = field;
    }
}
