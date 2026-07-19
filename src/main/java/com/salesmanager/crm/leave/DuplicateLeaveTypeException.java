package com.salesmanager.crm.leave;

/**
 * Thrown when creating a leave type whose (organization, code) would collide with an existing
 * one. Mapped to 409 Conflict by GlobalExceptionHandler - same rationale as
 * masterdata.DuplicateMasterDataException, whose shape this mirrors.
 */
public class DuplicateLeaveTypeException extends RuntimeException {

    public DuplicateLeaveTypeException(String message) {
        super(message);
    }
}
