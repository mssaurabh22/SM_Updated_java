package com.salesmanager.crm.leave;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.leave.dto.EmployeeLeaveAttendanceSummaryResponse;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan B.5's consolidated "Employee Leave & Attendance detail page" backing endpoint - a single
 * read composing existing leave-balance/leave-request-history/attendance-summary data for one
 * employee, not a new domain (see EmployeeLeaveAttendanceSummaryService for the composition and
 * access control). Requires EMPLOYEE_LEAVE_MANAGEMENT, per Part B of the entitlement plan.
 */
@RestController
public class EmployeeLeaveAttendanceSummaryController {

    private final EmployeeLeaveAttendanceSummaryService employeeLeaveAttendanceSummaryService;

    public EmployeeLeaveAttendanceSummaryController(
            EmployeeLeaveAttendanceSummaryService employeeLeaveAttendanceSummaryService) {
        this.employeeLeaveAttendanceSummaryService = employeeLeaveAttendanceSummaryService;
    }

    @GetMapping("/employees/{employeeId}/leave-attendance-summary")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public EmployeeLeaveAttendanceSummaryResponse get(@PathVariable UUID employeeId,
                                                        @RequestParam(required = false) Integer year,
                                                        @RequestParam(required = false) String attendanceMonth) {
        YearMonth resolvedMonth = attendanceMonth != null ? YearMonth.parse(attendanceMonth) : null;
        return employeeLeaveAttendanceSummaryService.getSummary(employeeId, year, resolvedMonth);
    }
}
