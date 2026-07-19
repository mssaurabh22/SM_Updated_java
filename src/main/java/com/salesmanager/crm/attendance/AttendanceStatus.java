package com.salesmanager.crm.attendance;

/**
 * Derived, never stored (see AttendanceRecord's class javadoc). AttendanceService#deriveStatus
 * computes exactly one of these per calendar day, in this priority order: PRESENT if
 * checkInAt is set; else ON_LEAVE if an APPROVED leave.LeaveRequest covers the date; else
 * HOLIDAY if a leave.Holiday row matches the date; else WEEKEND if Saturday/Sunday; else ABSENT
 * (a working day, no clock-in, no leave, no holiday).
 */
public enum AttendanceStatus {
    PRESENT,
    ABSENT,
    ON_LEAVE,
    HOLIDAY,
    WEEKEND
}
