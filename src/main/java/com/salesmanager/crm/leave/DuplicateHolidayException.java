package com.salesmanager.crm.leave;

/**
 * Thrown when creating a holiday whose (organization, holiday_date) would collide with an
 * existing one. Mapped to 409 Conflict by GlobalExceptionHandler - avoids a bare 500 from the
 * table's underlying unique constraint.
 */
public class DuplicateHolidayException extends RuntimeException {

    public DuplicateHolidayException(String message) {
        super(message);
    }
}
