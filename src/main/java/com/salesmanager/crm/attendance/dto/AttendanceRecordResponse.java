package com.salesmanager.crm.attendance.dto;

import com.salesmanager.crm.attendance.AttendanceRecord;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Read-only projection of an AttendanceRecord - backs the clock-in/clock-out responses. */
public record AttendanceRecordResponse(
        UUID id,
        UUID organizationId,
        UUID employeeId,
        LocalDate attendanceDate,
        OffsetDateTime checkInAt,
        OffsetDateTime checkOutAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AttendanceRecordResponse from(AttendanceRecord record) {
        return new AttendanceRecordResponse(
                record.getId(),
                record.getOrganizationId(),
                record.getEmployeeId(),
                record.getAttendanceDate(),
                record.getCheckInAt(),
                record.getCheckOutAt(),
                record.getCreatedAt(),
                record.getUpdatedAt());
    }
}
