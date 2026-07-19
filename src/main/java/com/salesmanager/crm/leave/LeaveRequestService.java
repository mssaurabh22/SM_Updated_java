package com.salesmanager.crm.leave;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.employeeactivity.EmployeeActivityLogService;
import com.salesmanager.crm.employeeactivity.EmployeeActivityType;
import com.salesmanager.crm.leave.dto.LeaveBalanceResponse;
import com.salesmanager.crm.leave.dto.LeaveRequestCreateRequest;
import com.salesmanager.crm.masterdata.InvalidReferenceException;
import com.salesmanager.crm.notification.NotificationService;
import com.salesmanager.crm.notification.NotificationType;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the LeaveRequest lifecycle (plan B.3): submission (with weekend/holiday-aware
 * totalDays computation, over-allocation hard-block, overlap-conflict rejection, and
 * direct-manager-or-Admin-fallback approver resolution - falling back to Admin when the
 * requester has no manager set OR their resolved manager is inactive), approve/reject (by the
 * resolved approver or any Admin override), and cancel (by the requesting employee, while
 * PENDING, or while APPROVED if the leave hasn't started yet) - each transition also writes an
 * EmployeeActivityLog entry and sends the relevant NotificationType.
 */
@Service
public class LeaveRequestService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final HolidayRepository holidayRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final NotificationService notificationService;
    private final EmployeeActivityLogService employeeActivityLogService;
    private final EmployeeLeaveBalanceService employeeLeaveBalanceService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public LeaveRequestService(LeaveRequestRepository leaveRequestRepository,
                                LeaveTypeRepository leaveTypeRepository,
                                HolidayRepository holidayRepository,
                                EmployeeRepository employeeRepository,
                                EmployeeHierarchyService employeeHierarchyService,
                                NotificationService notificationService,
                                EmployeeActivityLogService employeeActivityLogService,
                                EmployeeLeaveBalanceService employeeLeaveBalanceService,
                                CurrentUser currentUser,
                                ObjectMapper objectMapper) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.holidayRepository = holidayRepository;
        this.employeeRepository = employeeRepository;
        this.employeeHierarchyService = employeeHierarchyService;
        this.notificationService = notificationService;
        this.employeeActivityLogService = employeeActivityLogService;
        this.employeeLeaveBalanceService = employeeLeaveBalanceService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    // noRollbackFor is essential, not cosmetic - see MasterDataService#create's identical
    // comment: TenantFilter wraps the whole request in one shared transaction, so an unmarked
    // RuntimeException here would poison it even though GlobalExceptionHandler translates each
    // of these into a normal 4xx response.
    @Transactional(noRollbackFor = {InvalidReferenceException.class, LeaveRequestConflictException.class,
            InsufficientLeaveBalanceException.class})
    public LeaveRequest create(LeaveRequestCreateRequest request) {
        UUID employeeId = currentUser.get().getEmployeeId();

        LeaveType leaveType = leaveTypeRepository.findById(request.leaveTypeId())
                .filter(LeaveType::isActive)
                .orElseThrow(() -> new InvalidReferenceException("leaveTypeId",
                        "leaveTypeId does not reference an active leave type in this organization"));

        if (request.endDate().isBefore(request.startDate())) {
            throw new InvalidReferenceException("endDate", "endDate must not be before startDate");
        }

        BigDecimal totalDays = computeWorkingDays(request.startDate(), request.endDate());
        if (totalDays.signum() == 0) {
            throw new InvalidReferenceException("startDate",
                    "The selected date range contains no working days (all weekend/holiday)");
        }

        // Plan B.3, point 1: hard-blocks over-allocation by default on submission (an Admin can
        // still approve an over-limit request explicitly as an override at decision time - this
        // check only guards the convenience/default path of submitting one in the first place).
        BigDecimal remainingDays = employeeLeaveBalanceService.getBalanceSummary(employeeId, request.startDate().getYear())
                .stream()
                .filter(balance -> balance.leaveTypeId().equals(leaveType.getId()))
                .map(LeaveBalanceResponse::remainingDays)
                .findFirst()
                .orElse(BigDecimal.ZERO);
        if (totalDays.compareTo(remainingDays) > 0) {
            throw new InsufficientLeaveBalanceException("Requested " + totalDays + " day(s) but only "
                    + remainingDays + " remaining for " + leaveType.getName());
        }

        if (leaveRequestRepository.existsOverlapping(employeeId, request.startDate(), request.endDate())) {
            throw new LeaveRequestConflictException(
                    "You already have a pending or approved leave request overlapping these dates");
        }

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found: " + employeeId));
        // Plan B.2, point 1: falls back to the Admin pool (approverId left null - handled below)
        // not just when there's no manager set, but also when the resolved manager is inactive -
        // avoids routing a request to a manager who can no longer act on it.
        UUID managerId = employee.getManagerId();
        UUID approverId = null;
        if (managerId != null) {
            boolean managerActive = employeeRepository.findById(managerId)
                    .map(Employee::isActive)
                    .orElse(false);
            if (managerActive) {
                approverId = managerId;
            }
        }

        LeaveRequest leaveRequest = LeaveRequest.builder()
                .employeeId(employeeId)
                .leaveTypeId(leaveType.getId())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .totalDays(totalDays)
                .reason(request.reason())
                .status(LeaveRequestStatus.PENDING)
                .approverId(approverId)
                .build();
        // saveAndFlush - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        LeaveRequest saved = leaveRequestRepository.saveAndFlush(leaveRequest);

        employeeActivityLogService.record(employeeId, EmployeeActivityType.LEAVE_REQUEST_SUBMITTED, employeeId,
                "Leave request submitted for " + totalDays + " day(s)");

        String payload = buildPayload(saved);
        if (approverId != null) {
            notificationService.create(approverId, NotificationType.LEAVE_REQUEST_SUBMITTED, payload);
        } else {
            // No manager set - route to every ADMIN in the org as the fallback (plan B.2).
            for (Employee admin : employeeRepository.findByOrganizationIdAndRole(employee.getOrganizationId(), Role.ADMIN)) {
                notificationService.create(admin.getId(), NotificationType.LEAVE_REQUEST_SUBMITTED, payload);
            }
        }

        return saved;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidLeaveRequestStateException.class})
    public LeaveRequest approve(UUID id, String decisionNote) {
        return decide(id, decisionNote, LeaveRequestStatus.APPROVED,
                NotificationType.LEAVE_REQUEST_APPROVED, "approved");
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidLeaveRequestStateException.class})
    public LeaveRequest reject(UUID id, String decisionNote) {
        return decide(id, decisionNote, LeaveRequestStatus.REJECTED,
                NotificationType.LEAVE_REQUEST_REJECTED, "rejected");
    }

    private LeaveRequest decide(UUID id, String decisionNote, LeaveRequestStatus newStatus,
                                 NotificationType notificationType, String verb) {
        LeaveRequest leaveRequest = leaveRequestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Leave request not found: " + id));

        UserPrincipal principal = currentUser.get();
        boolean isResolvedApprover = leaveRequest.getApproverId() != null
                && leaveRequest.getApproverId().equals(principal.getEmployeeId());
        boolean isAdmin = principal.getRole() == Role.ADMIN;
        // Not the resolved approver and not an Admin fallback/override - information-hiding
        // NotFoundException (not a 403), same idiom as Lead#loadForCurrentUser/
        // NotificationService#markRead: "not yours to act on" is indistinguishable from
        // "doesn't exist", since a leave-request id was never shared with an unrelated employee
        // in the first place.
        if (!isResolvedApprover && !isAdmin) {
            throw new NotFoundException("Leave request not found: " + id);
        }
        if (leaveRequest.getStatus() != LeaveRequestStatus.PENDING) {
            throw new InvalidLeaveRequestStateException("Leave request has already been decided");
        }

        leaveRequest.setStatus(newStatus);
        leaveRequest.setDecidedById(principal.getEmployeeId());
        leaveRequest.setDecidedAt(OffsetDateTime.now());
        leaveRequest.setDecisionNote(decisionNote);
        // saveAndFlush - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        LeaveRequest saved = leaveRequestRepository.saveAndFlush(leaveRequest);

        EmployeeActivityType activityType = newStatus == LeaveRequestStatus.APPROVED
                ? EmployeeActivityType.LEAVE_REQUEST_APPROVED
                : EmployeeActivityType.LEAVE_REQUEST_REJECTED;
        employeeActivityLogService.record(saved.getEmployeeId(), activityType, principal.getEmployeeId(),
                "Leave request " + verb);

        notificationService.create(saved.getEmployeeId(), notificationType, buildPayload(saved));

        return saved;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidLeaveRequestStateException.class})
    public LeaveRequest cancel(UUID id) {
        LeaveRequest leaveRequest = leaveRequestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Leave request not found: " + id));
        UUID employeeId = currentUser.get().getEmployeeId();
        // Same information-hiding idiom as decide() above - a colleague's request id is
        // indistinguishable from a nonexistent one.
        if (!leaveRequest.getEmployeeId().equals(employeeId)) {
            throw new NotFoundException("Leave request not found: " + id);
        }
        // Plan B.3, point 5: cancellable while PENDING, or while APPROVED if the leave hasn't
        // started yet. Cancelling an APPROVED-but-not-yet-started request needs no other change
        // beyond flipping status to CANCELLED - EmployeeLeaveBalanceService#getBalanceSummary's
        // usedDays is always derived live from APPROVED rows (see LeaveRequestRepository#
        // sumApprovedDays), so a cancelled request automatically stops counting as used.
        LeaveRequestStatus status = leaveRequest.getStatus();
        boolean cancellable = status == LeaveRequestStatus.PENDING
                || (status == LeaveRequestStatus.APPROVED && leaveRequest.getStartDate().isAfter(LocalDate.now()));
        if (!cancellable) {
            throw new InvalidLeaveRequestStateException(
                    "Only a pending leave request, or an approved leave request that hasn't started yet, can be cancelled");
        }
        leaveRequest.setStatus(LeaveRequestStatus.CANCELLED);
        // saveAndFlush - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        LeaveRequest saved = leaveRequestRepository.saveAndFlush(leaveRequest);

        // No notification - this is the employee's own action, not something they need to be
        // told about.
        employeeActivityLogService.record(saved.getEmployeeId(), EmployeeActivityType.LEAVE_REQUEST_CANCELLED,
                employeeId, "Leave request cancelled");

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<LeaveRequest> listMine(LeaveRequestStatus status, Pageable pageable) {
        UUID employeeId = currentUser.get().getEmployeeId();
        if (status != null) {
            return leaveRequestRepository.findByEmployeeIdAndStatus(employeeId, status, pageable);
        }
        return leaveRequestRepository.findByEmployeeId(employeeId, pageable);
    }

    /**
     * "Pending My Approval": rows resolved directly to the caller (approverId = caller,
     * status PENDING), plus - for an ADMIN caller only - the unassigned fallback pool
     * (approverId IS NULL, status PENDING). Returned as a plain List, not a Page: correctly
     * merging two independently-paged repository queries into one Page is unnecessary
     * complexity for what is, in practice, a single manager's/org's small pending-approval
     * inbox - simplest-correct wins over full pagination here.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequest> listPendingMyApproval() {
        UserPrincipal principal = currentUser.get();
        List<LeaveRequest> resolvedToMe = leaveRequestRepository
                .findByApproverIdAndStatus(principal.getEmployeeId(), LeaveRequestStatus.PENDING);
        if (principal.getRole() != Role.ADMIN) {
            return resolvedToMe;
        }

        List<LeaveRequest> combined = new ArrayList<>(resolvedToMe);
        combined.addAll(leaveRequestRepository.findUnassignedPending());
        combined.sort(Comparator.comparing(LeaveRequest::getCreatedAt).reversed());
        return combined;
    }

    /**
     * Team Leave Calendar (plan B.4): every APPROVED leave request overlapping {@code month},
     * scoped to the caller's visible team - an ADMIN sees every approved request org-wide (no
     * employee-id filtering at all); anyone else sees only their subordinate chain's (via
     * EmployeeHierarchyService#getAllSubordinateIds) - an individual contributor with no reports
     * simply gets an empty list, same "empty state, not a bug" philosophy as
     * #listPendingMyApproval.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequest> listTeamCalendar(YearMonth month) {
        UserPrincipal principal = currentUser.get();
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        if (principal.getRole() == Role.ADMIN) {
            return leaveRequestRepository.findApprovedOverlappingOrgWide(start, end);
        }
        Set<UUID> subordinateIds = employeeHierarchyService.getAllSubordinateIds(principal.getEmployeeId());
        if (subordinateIds.isEmpty()) {
            return List.of();
        }
        return leaveRequestRepository.findApprovedOverlappingForEmployees(subordinateIds, start, end);
    }

    /** ADMIN-only, org-wide "All Requests" view (plan B.5), filterable via LeaveRequestSpecifications. */
    @Transactional(readOnly = true)
    public Page<LeaveRequest> listAll(LeaveRequestFilter filter, Pageable pageable) {
        Specification<LeaveRequest> spec = Specification
                .where(LeaveRequestSpecifications.hasEmployeeId(filter.employeeId()))
                .and(LeaveRequestSpecifications.hasStatus(filter.status()))
                .and(LeaveRequestSpecifications.hasLeaveTypeId(filter.leaveTypeId()))
                .and(LeaveRequestSpecifications.startDateFrom(filter.startDateFrom()))
                .and(LeaveRequestSpecifications.startDateTo(filter.startDateTo()));
        return leaveRequestRepository.findAll(spec, pageable);
    }

    /**
     * Counts calendar days in [start, end] inclusive that are neither Saturday/Sunday nor a
     * row in this org's holidays table for that date. Simple loop over the date range -
     * ranges here are realistically small (individual leave requests), no need to over-optimize.
     */
    private BigDecimal computeWorkingDays(LocalDate start, LocalDate end) {
        Set<LocalDate> holidayDates = new HashSet<>();
        for (Holiday holiday : holidayRepository.findByHolidayDateBetween(start, end)) {
            holidayDates.add(holiday.getHolidayDate());
        }
        int count = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            boolean isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
            if (!isWeekend && !holidayDates.contains(date)) {
                count++;
            }
        }
        return BigDecimal.valueOf(count);
    }

    /** Small hand-built JSON payload for LEAVE_REQUEST_* notifications - same style as LeadService#buildReassignmentPayload. */
    private String buildPayload(LeaveRequest leaveRequest) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "leaveRequestId", leaveRequest.getId().toString(),
                    "leaveTypeId", leaveRequest.getLeaveTypeId().toString(),
                    "startDate", leaveRequest.getStartDate().toString(),
                    "endDate", leaveRequest.getEndDate().toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize leave request notification payload", e);
        }
    }
}
