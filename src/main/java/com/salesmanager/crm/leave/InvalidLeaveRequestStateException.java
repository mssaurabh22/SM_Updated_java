package com.salesmanager.crm.leave;

/**
 * Thrown when approve/reject/cancel is attempted on a LeaveRequest that is no longer PENDING
 * (already decided, or already cancelled). Mapped to 409 Conflict by GlobalExceptionHandler -
 * the request exists and the caller is authorized to act on it, but its current state doesn't
 * permit the requested transition, which is a conflict, not a bad request or a 404.
 */
public class InvalidLeaveRequestStateException extends RuntimeException {

    public InvalidLeaveRequestStateException(String message) {
        super(message);
    }
}
