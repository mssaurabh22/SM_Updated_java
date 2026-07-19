package com.salesmanager.crm.attendance.dto;

import com.salesmanager.crm.attendance.AttendanceStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One derived calendar day - whether or not an AttendanceRecord row actually exists for it (a
 * Weekend/Holiday/Absent day usually has no row at all; only a day with a check-in does). See
 * AttendanceStatus's javadoc for the derivation priority order.
 */
public record AttendanceDayResponse(
        LocalDate date,
        AttendanceStatus status,
        OffsetDateTime checkInAt,
        OffsetDateTime checkOutAt) {
}
