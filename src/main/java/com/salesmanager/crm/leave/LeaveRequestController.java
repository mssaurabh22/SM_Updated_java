package com.salesmanager.crm.leave;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.leave.dto.LeaveRequestCreateRequest;
import com.salesmanager.crm.leave.dto.LeaveRequestDecisionRequest;
import com.salesmanager.crm.leave.dto.LeaveRequestResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - LeaveRequestService enforces every ownership/routing/state rule (submitter
 * derived from CurrentUser, never client-supplied, same golden rule as organizationId; approver-
 * or-Admin authorization on approve/reject; requester-only on cancel). Every endpoint requires
 * EMPLOYEE_LEAVE_MANAGEMENT, per Part B of the entitlement plan.
 */
@RestController
@RequestMapping("/leave-requests")
public class LeaveRequestController {

    private final LeaveRequestService leaveRequestService;

    public LeaveRequestController(LeaveRequestService leaveRequestService) {
        this.leaveRequestService = leaveRequestService;
    }

    @PostMapping
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestResponse create(@Valid @RequestBody LeaveRequestCreateRequest request) {
        return LeaveRequestResponse.from(leaveRequestService.create(request));
    }

    @GetMapping("/mine")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public Page<LeaveRequestResponse> mine(@RequestParam(required = false) LeaveRequestStatus status,
                                            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return leaveRequestService.listMine(status, pageable).map(LeaveRequestResponse::from);
    }

    @GetMapping("/pending-approval")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<LeaveRequestResponse> pendingApproval() {
        return leaveRequestService.listPendingMyApproval().stream()
                .map(LeaveRequestResponse::from)
                .toList();
    }

    @PatchMapping("/{id}/approve")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public LeaveRequestResponse approve(@PathVariable UUID id,
                                         @Valid @RequestBody(required = false) LeaveRequestDecisionRequest request) {
        String decisionNote = request != null ? request.decisionNote() : null;
        return LeaveRequestResponse.from(leaveRequestService.approve(id, decisionNote));
    }

    @PatchMapping("/{id}/reject")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public LeaveRequestResponse reject(@PathVariable UUID id,
                                        @Valid @RequestBody(required = false) LeaveRequestDecisionRequest request) {
        String decisionNote = request != null ? request.decisionNote() : null;
        return LeaveRequestResponse.from(leaveRequestService.reject(id, decisionNote));
    }

    /**
     * Team Leave Calendar (plan B.4) - any authenticated user, not ADMIN-only: a manager who
     * isn't an Admin needs this too. Scope is resolved entirely inside
     * LeaveRequestService#listTeamCalendar (ADMIN -> whole org, else -> the caller's subordinate
     * chain). {@code month} defaults to the current month when omitted, same convention as
     * attendance.AttendanceController#resolveYearMonth.
     */
    @GetMapping("/team-calendar")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<LeaveRequestResponse> teamCalendar(@RequestParam(required = false) String month) {
        YearMonth yearMonth = month != null ? YearMonth.parse(month) : YearMonth.now();
        return leaveRequestService.listTeamCalendar(yearMonth).stream()
                .map(LeaveRequestResponse::from)
                .toList();
    }

    @PatchMapping("/{id}/cancel")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public LeaveRequestResponse cancel(@PathVariable UUID id) {
        return LeaveRequestResponse.from(leaveRequestService.cancel(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public Page<LeaveRequestResponse> listAll(@RequestParam(required = false) UUID employeeId,
                                               @RequestParam(required = false) LeaveRequestStatus status,
                                               @RequestParam(required = false) UUID leaveTypeId,
                                               @RequestParam(required = false) LocalDate startDateFrom,
                                               @RequestParam(required = false) LocalDate startDateTo,
                                               @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        LeaveRequestFilter filter = new LeaveRequestFilter(employeeId, status, leaveTypeId, startDateFrom, startDateTo);
        return leaveRequestService.listAll(filter, pageable).map(LeaveRequestResponse::from);
    }
}
