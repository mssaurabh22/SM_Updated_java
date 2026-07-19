package com.salesmanager.crm.leave.dto;

import com.salesmanager.crm.attendance.dto.AttendanceSummaryResponse;
import java.util.List;

/**
 * Plan B.5's "Employee Leave & Attendance detail page" backing response - a thin composition of
 * data that already exists across the leave and attendance packages (current balance per leave
 * type, recent leave-request history, and an attendance summary for one month), not a new
 * domain of its own. See EmployeeLeaveAttendanceSummaryService for the composition and access
 * control (viewable by the employee themselves, their direct manager, or any Admin).
 */
public record EmployeeLeaveAttendanceSummaryResponse(
        List<LeaveBalanceResponse> balances,
        List<LeaveRequestResponse> recentRequests,
        AttendanceSummaryResponse attendanceSummary) {
}
