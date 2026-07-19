package com.salesmanager.crm.attendance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here.
 */
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    /** Backs clock-in/clock-out idempotency (AttendanceService#clockIn/#clockOut) - today's row, if any. */
    Optional<AttendanceRecord> findByEmployeeIdAndAttendanceDate(UUID employeeId, LocalDate attendanceDate);

    /** Backs AttendanceService#getMonthCalendar/#getSummary - one batch fetch for a whole date range. */
    List<AttendanceRecord> findByEmployeeIdAndAttendanceDateBetween(UUID employeeId, LocalDate start, LocalDate end);

    /**
     * Backs AttendanceService#countAbsentToday (plan B.5's HR dashboard) - one batch fetch of a
     * whole scope of employees' attendance rows for a single date, rather than one query per
     * employee.
     */
    List<AttendanceRecord> findByEmployeeIdInAndAttendanceDate(Collection<UUID> employeeIds, LocalDate attendanceDate);
}
