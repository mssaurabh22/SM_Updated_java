package com.salesmanager.crm.attendance;

/**
 * Thrown by AttendanceService#clockIn when the caller already has a check-in timestamp recorded
 * for today. Deliberately rejects rather than silently no-op-ing a second clock-in: a silent
 * no-op would let an employee believe a second clock-in "worked" (e.g. after forgetting they
 * already clocked in) without ever surfacing that their original check-in time is what actually
 * stuck - an explicit rejection is more honest about what state the record is actually in.
 * Mapped to 409 Conflict by GlobalExceptionHandler - the record exists and is well-formed, this
 * action just doesn't apply to its current state, same rationale as
 * leave.InvalidLeaveRequestStateException.
 */
public class AlreadyClockedInException extends RuntimeException {

    public AlreadyClockedInException(String message) {
        super(message);
    }
}
