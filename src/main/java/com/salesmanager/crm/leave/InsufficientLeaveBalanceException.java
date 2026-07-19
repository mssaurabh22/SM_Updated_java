package com.salesmanager.crm.leave;

/**
 * Thrown when submitting a leave request whose totalDays exceeds the employee's remaining
 * balance for that leave type/year (plan B.3, point 1: submission hard-blocks over-allocation
 * by default - an Admin can still approve an over-limit request explicitly as an override, this
 * exception only guards the submission convenience path). Mapped to 409 Conflict by
 * GlobalExceptionHandler - same rationale/status as LeaveRequestConflictException for a
 * different kind of "the request is well-formed but conflicts with existing state" condition.
 */
public class InsufficientLeaveBalanceException extends RuntimeException {

    public InsufficientLeaveBalanceException(String message) {
        super(message);
    }
}
