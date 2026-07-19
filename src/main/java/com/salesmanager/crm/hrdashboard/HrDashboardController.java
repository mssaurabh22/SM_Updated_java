package com.salesmanager.crm.hrdashboard;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.hrdashboard.dto.HrDashboardSnapshotResponse;
import com.salesmanager.crm.hrdashboard.dto.LeaveUtilizationResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - HrDashboardService derives every aggregate (plan B.5). Both endpoints are
 * scope-based rather than role-gated with @PreAuthorize: an ADMIN naturally gets the whole-org
 * view and anyone else gets their own team (or an empty one), all resolved inside the service
 * via employee.EmployeeHierarchyService - same "each section fetches independently" split as
 * reporting.ReportingController.
 */
@RestController
@RequestMapping("/hr-dashboard")
public class HrDashboardController {

    private final HrDashboardService hrDashboardService;

    public HrDashboardController(HrDashboardService hrDashboardService) {
        this.hrDashboardService = hrDashboardService;
    }

    @GetMapping("/today-snapshot")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public HrDashboardSnapshotResponse todaySnapshot() {
        return hrDashboardService.todaySnapshot();
    }

    @GetMapping("/leave-utilization")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<LeaveUtilizationResponse> leaveUtilization(@RequestParam(required = false) Integer year) {
        return hrDashboardService.leaveUtilization(year != null ? year : LocalDate.now().getYear());
    }
}
