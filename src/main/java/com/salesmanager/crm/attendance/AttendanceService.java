package com.salesmanager.crm.attendance;

import com.salesmanager.crm.attendance.dto.AttendanceDayResponse;
import com.salesmanager.crm.attendance.dto.AttendanceSummaryResponse;
import com.salesmanager.crm.leave.Holiday;
import com.salesmanager.crm.leave.HolidayRepository;
import com.salesmanager.crm.leave.LeaveRequest;
import com.salesmanager.crm.leave.LeaveRequestRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates AttendanceRecord (plan B.4): self-service clock-in/clock-out, and derived-status
 * calendar/summary views. {@code status} is never stored (see AttendanceRecord's class javadoc)
 * - every read here batch-fetches the AttendanceRecords, APPROVED leave.LeaveRequests, and
 * leave.Holidays for the whole requested range ONCE, then derives one AttendanceStatus per
 * calendar day in a plain Java loop (same "simplest correct, no need to over-optimize a
 * realistically small range" spirit as leave.LeaveRequestService#computeWorkingDays).
 */
@Service
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final HolidayRepository holidayRepository;

    public AttendanceService(AttendanceRecordRepository attendanceRecordRepository,
                              LeaveRequestRepository leaveRequestRepository,
                              HolidayRepository holidayRepository) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.holidayRepository = holidayRepository;
    }

    /**
     * Rejects (rather than silently no-op-ing) a second clock-in on the same day - see
     * AlreadyClockedInException's javadoc for why an explicit rejection is the more honest
     * choice here. A row only ever exists for today once this method has successfully created
     * it (with checkInAt already set), so "a record exists" and "already clocked in" are the
     * same condition - there is no create-without-checkInAt path to reconcile.
     */
    @Transactional(noRollbackFor = AlreadyClockedInException.class)
    public AttendanceRecord clockIn(UUID employeeId) {
        LocalDate today = LocalDate.now();
        if (attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(employeeId, today).isPresent()) {
            throw new AlreadyClockedInException("Already clocked in today");
        }
        AttendanceRecord record = AttendanceRecord.builder()
                .employeeId(employeeId)
                .attendanceDate(today)
                .checkInAt(OffsetDateTime.now())
                .build();
        // saveAndFlush - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        return attendanceRecordRepository.saveAndFlush(record);
    }

    @Transactional(noRollbackFor = InvalidAttendanceStateException.class)
    public AttendanceRecord clockOut(UUID employeeId) {
        LocalDate today = LocalDate.now();
        AttendanceRecord record = attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(employeeId, today)
                .filter(r -> r.getCheckInAt() != null)
                .orElseThrow(() -> new InvalidAttendanceStateException("Cannot clock out before clocking in today"));
        record.setCheckOutAt(OffsetDateTime.now());
        // saveAndFlush - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        return attendanceRecordRepository.saveAndFlush(record);
    }

    /** Personal Attendance Calendar (plan B.4) - every calendar day of {@code yearMonth}, derived. */
    @Transactional(readOnly = true)
    public List<AttendanceDayResponse> getMonthCalendar(UUID employeeId, YearMonth yearMonth) {
        return deriveDays(employeeId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
    }

    /**
     * Arbitrary caller-supplied range (used by the Employee Leave & Attendance detail page,
     * plan B.5) - same per-day derivation as {@link #getMonthCalendar}, plus headline counts per
     * status so a detail page can render without re-deriving them itself.
     */
    @Transactional(readOnly = true)
    public AttendanceSummaryResponse getSummary(UUID employeeId, LocalDate startDate, LocalDate endDate) {
        List<AttendanceDayResponse> days = deriveDays(employeeId, startDate, endDate);
        int present = 0;
        int absent = 0;
        int onLeave = 0;
        int holiday = 0;
        int weekend = 0;
        for (AttendanceDayResponse day : days) {
            switch (day.status()) {
                case PRESENT -> present++;
                case ABSENT -> absent++;
                case ON_LEAVE -> onLeave++;
                case HOLIDAY -> holiday++;
                case WEEKEND -> weekend++;
            }
        }
        return new AttendanceSummaryResponse(present, absent, onLeave, holiday, weekend, days);
    }

    /**
     * HR dashboard's scope-wide "haven't clocked in today" count (plan B.5's notClockedInToday).
     * Reuses the exact same Weekend/Holiday/OnLeave/Absent priority rule as
     * {@link #deriveStatus} rather than hand-rolling a second, subtly-different copy of it:
     * today being a Weekend or an org Holiday means nobody in scope can derive to ABSENT (both
     * outrank Absent in the priority order), so this short-circuits to 0 in that case exactly
     * like deriveStatus would for every employee. Otherwise, one batch fetch of today's
     * AttendanceRecords and today's APPROVED-overlapping LeaveRequests across the whole scope
     * (not one query per employee), then a plain Java loop - same "batch-fetch once, derive in a
     * loop over a bounded set" spirit as {@link #deriveDays}.
     */
    @Transactional(readOnly = true)
    public int countAbsentToday(Set<UUID> employeeIds, LocalDate date) {
        if (employeeIds.isEmpty()) {
            return 0;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return 0;
        }
        if (!holidayRepository.findByHolidayDateBetween(date, date).isEmpty()) {
            return 0;
        }

        Set<UUID> checkedInEmployeeIds = new HashSet<>();
        for (AttendanceRecord record : attendanceRecordRepository.findByEmployeeIdInAndAttendanceDate(employeeIds, date)) {
            if (record.getCheckInAt() != null) {
                checkedInEmployeeIds.add(record.getEmployeeId());
            }
        }

        Set<UUID> onLeaveEmployeeIds = new HashSet<>();
        for (LeaveRequest leaveRequest : leaveRequestRepository.findApprovedOverlappingForEmployees(employeeIds, date, date)) {
            onLeaveEmployeeIds.add(leaveRequest.getEmployeeId());
        }

        int absentCount = 0;
        for (UUID employeeId : employeeIds) {
            if (!checkedInEmployeeIds.contains(employeeId) && !onLeaveEmployeeIds.contains(employeeId)) {
                absentCount++;
            }
        }
        return absentCount;
    }

    private List<AttendanceDayResponse> deriveDays(UUID employeeId, LocalDate start, LocalDate end) {
        Map<LocalDate, AttendanceRecord> recordsByDate = new HashMap<>();
        for (AttendanceRecord record : attendanceRecordRepository
                .findByEmployeeIdAndAttendanceDateBetween(employeeId, start, end)) {
            recordsByDate.put(record.getAttendanceDate(), record);
        }

        Set<LocalDate> leaveDates = new HashSet<>();
        for (LeaveRequest leaveRequest : leaveRequestRepository.findApprovedOverlapping(employeeId, start, end)) {
            LocalDate rangeStart = leaveRequest.getStartDate().isBefore(start) ? start : leaveRequest.getStartDate();
            LocalDate rangeEnd = leaveRequest.getEndDate().isAfter(end) ? end : leaveRequest.getEndDate();
            for (LocalDate date = rangeStart; !date.isAfter(rangeEnd); date = date.plusDays(1)) {
                leaveDates.add(date);
            }
        }

        Set<LocalDate> holidayDates = new HashSet<>();
        for (Holiday holiday : holidayRepository.findByHolidayDateBetween(start, end)) {
            holidayDates.add(holiday.getHolidayDate());
        }

        List<AttendanceDayResponse> days = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            AttendanceRecord record = recordsByDate.get(date);
            OffsetDateTime checkInAt = record != null ? record.getCheckInAt() : null;
            OffsetDateTime checkOutAt = record != null ? record.getCheckOutAt() : null;
            AttendanceStatus status = deriveStatus(date, checkInAt, leaveDates, holidayDates);
            days.add(new AttendanceDayResponse(date, status, checkInAt, checkOutAt));
        }
        return days;
    }

    /** See AttendanceStatus's class javadoc for this exact priority order. */
    private AttendanceStatus deriveStatus(LocalDate date, OffsetDateTime checkInAt,
                                           Set<LocalDate> leaveDates, Set<LocalDate> holidayDates) {
        if (checkInAt != null) {
            return AttendanceStatus.PRESENT;
        }
        if (leaveDates.contains(date)) {
            return AttendanceStatus.ON_LEAVE;
        }
        if (holidayDates.contains(date)) {
            return AttendanceStatus.HOLIDAY;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return AttendanceStatus.WEEKEND;
        }
        return AttendanceStatus.ABSENT;
    }
}
