package com.salesmanager.crm.attendance.dto;

import java.util.List;

/**
 * Headline per-status day counts for a date range, computed from the same derived
 * {@link AttendanceDayResponse} list (AttendanceService#getSummary) - lets a detail page (plan
 * B.5) render counts directly rather than re-deriving them client-side from the raw day list.
 */
public record AttendanceSummaryResponse(
        int presentDays,
        int absentDays,
        int onLeaveDays,
        int holidayDays,
        int weekendDays,
        List<AttendanceDayResponse> days) {
}
