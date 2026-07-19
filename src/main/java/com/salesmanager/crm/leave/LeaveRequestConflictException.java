package com.salesmanager.crm.leave;

/**
 * Thrown when submitting a leave request whose date range overlaps another PENDING or APPROVED
 * request already held by the same employee. Mapped to 409 Conflict by GlobalExceptionHandler -
 * same rationale/status as masterdata.DuplicateMasterDataException for a different kind of
 * uniqueness violation (overlapping ranges rather than a duplicate code).
 */
public class LeaveRequestConflictException extends RuntimeException {

    public LeaveRequestConflictException(String message) {
        super(message);
    }
}
