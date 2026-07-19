package com.salesmanager.crm.leave;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.leave.dto.LeaveBalanceResponse;
import com.salesmanager.crm.leave.dto.LeaveBalanceSetRequest;
import com.salesmanager.crm.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read/adjust an employee's per-leave-type balance summary (plan B.1). GET /mine is any
 * authenticated employee viewing their own; GET /employee/{employeeId} and the PUT allocation
 * endpoint are ADMIN-only for this slice - a documented simplification (a report's own manager
 * cannot view/set their balance yet; only ADMIN can) rather than building out manager-scoped
 * access before it's asked for. Every endpoint requires EMPLOYEE_LEAVE_MANAGEMENT, per Part B
 * of the entitlement plan.
 */
@RestController
public class LeaveBalanceController {

    private final EmployeeLeaveBalanceService employeeLeaveBalanceService;
    private final CurrentUser currentUser;

    public LeaveBalanceController(EmployeeLeaveBalanceService employeeLeaveBalanceService, CurrentUser currentUser) {
        this.employeeLeaveBalanceService = employeeLeaveBalanceService;
        this.currentUser = currentUser;
    }

    @GetMapping("/leave-balances/mine")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<LeaveBalanceResponse> mine(@RequestParam(required = false) Integer year) {
        UUID employeeId = currentUser.get().getEmployeeId();
        return employeeLeaveBalanceService.getBalanceSummary(employeeId, resolveYear(year));
    }

    @GetMapping("/leave-balances/employee/{employeeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<LeaveBalanceResponse> forEmployee(@PathVariable UUID employeeId,
                                                   @RequestParam(required = false) Integer year) {
        return employeeLeaveBalanceService.getBalanceSummary(employeeId, resolveYear(year));
    }

    @PutMapping("/leave-balances/employee/{employeeId}/leave-type/{leaveTypeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<LeaveBalanceResponse> setAllocation(@PathVariable UUID employeeId,
                                                     @PathVariable UUID leaveTypeId,
                                                     @RequestParam(required = false) Integer year,
                                                     @Valid @RequestBody LeaveBalanceSetRequest request) {
        int resolvedYear = resolveYear(year);
        employeeLeaveBalanceService.setAllocation(employeeId, leaveTypeId, resolvedYear,
                request.allocatedDays(), request.carriedForwardDays());
        return employeeLeaveBalanceService.getBalanceSummary(employeeId, resolvedYear);
    }

    private int resolveYear(Integer year) {
        return year != null ? year : Year.now().getValue();
    }
}
