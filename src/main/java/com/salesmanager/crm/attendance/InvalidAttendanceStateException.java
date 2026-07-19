package com.salesmanager.crm.attendance;

/**
 * Thrown by AttendanceService#clockOut when the caller has no check-in recorded yet for today
 * (there is nothing to clock out of). Mapped to 409 Conflict by GlobalExceptionHandler - same
 * rationale as leave.InvalidLeaveRequestStateException: the request is well-formed, but the
 * current state doesn't permit this transition.
 */
public class InvalidAttendanceStateException extends RuntimeException {

    public InvalidAttendanceStateException(String message) {
        super(message);
    }
}
