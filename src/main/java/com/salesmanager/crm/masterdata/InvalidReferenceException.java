package com.salesmanager.crm.masterdata;

import lombok.Getter;

/**
 * Thrown by {@link MasterDataService#validateReference} when a foreign field (e.g.
 * Employee.designationId) does not point at an in-tenant master-data row of the expected
 * type. Mapped to 400 Bad Request with a field-level message by GlobalExceptionHandler,
 * using the same ErrorResponse.FieldErrorDetail shape as Bean Validation failures.
 */
@Getter
public class InvalidReferenceException extends RuntimeException {

    private final String field;

    public InvalidReferenceException(String field, String message) {
        super(message);
        this.field = field;
    }
}
