package com.salesmanager.crm.attendance;

import com.salesmanager.crm.attendance.dto.AttendanceDayResponse;
import com.salesmanager.crm.attendance.dto.AttendanceRecordResponse;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.security.CurrentUser;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - AttendanceService derives everything, employeeId for clock-in/out/mine is
 * always taken from CurrentUser, never client-supplied, same golden rule as organizationId.
 * GET /attendance/employee/{employeeId} is ADMIN-only for this slice - same documented
 * simplification as LeaveBalanceController#forEmployee: "a report's own manager cannot view
 * this yet, only ADMIN can". Every endpoint requires EMPLOYEE_LEAVE_MANAGEMENT, per Part B of
 * the entitlement plan.
 */
@RestController
@RequestMapping("/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final CurrentUser currentUser;

    public AttendanceController(AttendanceService attendanceService, CurrentUser currentUser) {
        this.attendanceService = attendanceService;
        this.currentUser = currentUser;
    }

    @PostMapping("/clock-in")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceRecordResponse clockIn() {
        UUID employeeId = currentUser.get().getEmployeeId();
        return AttendanceRecordResponse.from(attendanceService.clockIn(employeeId));
    }

    @PostMapping("/clock-out")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public AttendanceRecordResponse clockOut() {
        UUID employeeId = currentUser.get().getEmployeeId();
        return AttendanceRecordResponse.from(attendanceService.clockOut(employeeId));
    }

    @GetMapping("/mine")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<AttendanceDayResponse> mine(@RequestParam(required = false) String yearMonth) {
        UUID employeeId = currentUser.get().getEmployeeId();
        return attendanceService.getMonthCalendar(employeeId, resolveYearMonth(yearMonth));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<AttendanceDayResponse> forEmployee(@PathVariable UUID employeeId,
                                                    @RequestParam(required = false) String yearMonth) {
        return attendanceService.getMonthCalendar(employeeId, resolveYearMonth(yearMonth));
    }

    private YearMonth resolveYearMonth(String yearMonth) {
        return yearMonth != null ? YearMonth.parse(yearMonth) : YearMonth.now();
    }
}
