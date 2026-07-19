package com.salesmanager.crm.hrdashboard;

import com.salesmanager.crm.attendance.AttendanceService;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.hrdashboard.dto.HrDashboardSnapshotResponse;
import com.salesmanager.crm.hrdashboard.dto.LeaveUtilizationResponse;
import com.salesmanager.crm.leave.LeaveRequest;
import com.salesmanager.crm.leave.LeaveRequestRepository;
import com.salesmanager.crm.leave.LeaveRequestService;
import com.salesmanager.crm.leave.LeaveType;
import com.salesmanager.crm.leave.LeaveTypeRepository;
import com.salesmanager.crm.leave.LeaveTypeUsageSum;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregates for the HR overview dashboard (plan B.5) - mirrors reporting.
 * ReportingService's "each section fetches independently, one slow endpoint doesn't block the
 * rest" philosophy: #todaySnapshot and #leaveUtilization are two entirely independent queries,
 * not one combined call. Both scope via employee.EmployeeHierarchyService#getVisibleEmployeeScope
 * (ADMIN -> whole org, else -> the caller's subordinate chain) - except pendingApprovalsForMe
 * inside #todaySnapshot, which is deliberately the caller's own personal inbox, not team-scoped.
 */
@Service
public class HrDashboardService {

    private final EmployeeHierarchyService employeeHierarchyService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final AttendanceService attendanceService;
    private final LeaveRequestService leaveRequestService;
    private final CurrentUser currentUser;

    public HrDashboardService(EmployeeHierarchyService employeeHierarchyService,
                               LeaveRequestRepository leaveRequestRepository,
                               LeaveTypeRepository leaveTypeRepository,
                               AttendanceService attendanceService,
                               LeaveRequestService leaveRequestService,
                               CurrentUser currentUser) {
        this.employeeHierarchyService = employeeHierarchyService;
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.attendanceService = attendanceService;
        this.leaveRequestService = leaveRequestService;
        this.currentUser = currentUser;
    }

    /**
     * onLeaveToday/notClockedInToday are scoped to the caller's visible team;
     * pendingApprovalsForMe is inherently personal (the caller's own resolved-approver inbox,
     * plus the Admin-fallback pool if they're an Admin) - reuses
     * LeaveRequestService#listPendingMyApproval as-is rather than re-deriving it.
     */
    @Transactional(readOnly = true)
    public HrDashboardSnapshotResponse todaySnapshot() {
        UserPrincipal principal = currentUser.get();
        Set<UUID> scope = employeeHierarchyService
                .getVisibleEmployeeScope(principal.getEmployeeId(), principal.getRole() == Role.ADMIN);
        LocalDate today = LocalDate.now();

        int onLeaveToday = 0;
        if (!scope.isEmpty()) {
            Set<UUID> onLeaveEmployeeIds = new HashSet<>();
            for (LeaveRequest leaveRequest : leaveRequestRepository.findApprovedOverlappingForEmployees(scope, today, today)) {
                onLeaveEmployeeIds.add(leaveRequest.getEmployeeId());
            }
            onLeaveToday = onLeaveEmployeeIds.size();
        }

        int notClockedInToday = attendanceService.countAbsentToday(scope, today);
        int pendingApprovalsForMe = leaveRequestService.listPendingMyApproval().size();

        return new HrDashboardSnapshotResponse(onLeaveToday, notClockedInToday, pendingApprovalsForMe);
    }

    /**
     * One row per active LeaveType, scoped the same way as #todaySnapshot (no personal-inbox
     * concept here - this is purely a team/org aggregate). averageDaysUsed always divides by the
     * full scope size (not just employees who used that type), and is 0 for every type when the
     * scope is empty - see LeaveUtilizationResponse's javadoc.
     */
    @Transactional(readOnly = true)
    public List<LeaveUtilizationResponse> leaveUtilization(int year) {
        UserPrincipal principal = currentUser.get();
        Set<UUID> scope = employeeHierarchyService
                .getVisibleEmployeeScope(principal.getEmployeeId(), principal.getRole() == Role.ADMIN);

        List<LeaveType> activeLeaveTypes =
                leaveTypeRepository.findByActive(true, Sort.by(Sort.Direction.ASC, "sortOrder"));

        Map<UUID, BigDecimal> usedByLeaveTypeId = new HashMap<>();
        if (!scope.isEmpty()) {
            for (LeaveTypeUsageSum row : leaveRequestRepository.sumApprovedDaysByLeaveTypeForEmployees(scope, year)) {
                usedByLeaveTypeId.put(row.getLeaveTypeId(), row.getTotalDays());
            }
        }

        List<LeaveUtilizationResponse> results = new ArrayList<>();
        for (LeaveType leaveType : activeLeaveTypes) {
            BigDecimal averageDaysUsed = BigDecimal.ZERO;
            if (!scope.isEmpty()) {
                BigDecimal totalUsed = usedByLeaveTypeId.getOrDefault(leaveType.getId(), BigDecimal.ZERO);
                averageDaysUsed = totalUsed.divide(BigDecimal.valueOf(scope.size()), 2, RoundingMode.HALF_UP);
            }
            results.add(new LeaveUtilizationResponse(leaveType.getId(), leaveType.getName(), averageDaysUsed));
        }
        return results;
    }
}
