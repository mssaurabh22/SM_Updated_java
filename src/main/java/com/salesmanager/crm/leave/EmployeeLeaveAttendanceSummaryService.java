package com.salesmanager.crm.leave;

import com.salesmanager.crm.attendance.AttendanceService;
import com.salesmanager.crm.attendance.dto.AttendanceSummaryResponse;
import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.leave.dto.EmployeeLeaveAttendanceSummaryResponse;
import com.salesmanager.crm.leave.dto.LeaveRequestResponse;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.time.Year;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the plan B.5 "Employee Leave & Attendance detail page" response out of data that
 * already exists across EmployeeLeaveBalanceService (balances), LeaveRequestRepository (recent
 * request history), and AttendanceService (a month's attendance summary) - deliberately no new
 * business logic here beyond access control, since none of the underlying data is new.
 *
 * Access control (plan B.5): viewable by the employee themselves, their DIRECT manager
 * (employee.managerId == caller.employeeId - direct only, no recursive hierarchy walk for this
 * slice; recursive "all subordinates at any depth" visibility is explicitly deferred to Phase
 * 5's Team Calendar/HR dashboard work per the plan's own B.2 note), or any ADMIN. Anyone else
 * gets an information-hiding NotFoundException, same idiom as LeaveRequestService#decide.
 */
@Service
public class EmployeeLeaveAttendanceSummaryService {

    private static final int RECENT_REQUESTS_LIMIT = 20;

    private final EmployeeRepository employeeRepository;
    private final EmployeeLeaveBalanceService employeeLeaveBalanceService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceService attendanceService;
    private final CurrentUser currentUser;

    public EmployeeLeaveAttendanceSummaryService(EmployeeRepository employeeRepository,
                                                  EmployeeLeaveBalanceService employeeLeaveBalanceService,
                                                  LeaveRequestRepository leaveRequestRepository,
                                                  AttendanceService attendanceService,
                                                  CurrentUser currentUser) {
        this.employeeRepository = employeeRepository;
        this.employeeLeaveBalanceService = employeeLeaveBalanceService;
        this.leaveRequestRepository = leaveRequestRepository;
        this.attendanceService = attendanceService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public EmployeeLeaveAttendanceSummaryResponse getSummary(UUID employeeId, Integer year, YearMonth attendanceMonth) {
        Employee target = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found: " + employeeId));

        UserPrincipal principal = currentUser.get();
        boolean isSelf = principal.getEmployeeId().equals(employeeId);
        boolean isAdmin = principal.getRole() == Role.ADMIN;
        boolean isDirectManager = principal.getEmployeeId().equals(target.getManagerId());
        // Not the employee, not their direct manager, not an Admin - information-hiding
        // NotFoundException (not a 403), same idiom as LeaveRequestService#decide/#cancel: "not
        // yours to view" is indistinguishable from "doesn't exist", since this employeeId was
        // never shared with an unrelated caller in the first place.
        if (!isSelf && !isDirectManager && !isAdmin) {
            throw new NotFoundException("Employee not found: " + employeeId);
        }

        int resolvedYear = year != null ? year : Year.now().getValue();
        YearMonth resolvedMonth = attendanceMonth != null ? attendanceMonth : YearMonth.now();

        var balances = employeeLeaveBalanceService.getBalanceSummary(employeeId, resolvedYear);
        var recentRequests = leaveRequestRepository
                .findByEmployeeId(employeeId, PageRequest.of(0, RECENT_REQUESTS_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(LeaveRequestResponse::from)
                .getContent();
        AttendanceSummaryResponse attendanceSummary = attendanceService.getSummary(employeeId,
                resolvedMonth.atDay(1), resolvedMonth.atEndOfMonth());

        return new EmployeeLeaveAttendanceSummaryResponse(balances, recentRequests, attendanceSummary);
    }
}
