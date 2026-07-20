package com.salesmanager.crm.employee;

import com.salesmanager.crm.entitlement.EntitlementService;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recursive visibility scoping (plan B.2): "who can this viewer see" beyond just their direct
 * reports. Shared by the Team Leave Calendar (leave.LeaveRequestService#listTeamCalendar) and
 * the HR overview dashboard (hrdashboard.HrDashboardService), both of which need "my direct +
 * indirect reports, at any depth" rather than a flat {@code WHERE manager_id = me}. Lives in
 * this package (not leave/hrdashboard) because it's fundamentally Employee-domain logic -
 * leave.LeaveRequestService already depends directly on EmployeeRepository, so this is a
 * natural, consistent home, reused by both consumer packages.
 */
@Service
public class EmployeeHierarchyService {

    private final EmployeeRepository employeeRepository;
    private final EntitlementService entitlementService;

    public EmployeeHierarchyService(EmployeeRepository employeeRepository,
                                     EntitlementService entitlementService) {
        this.employeeRepository = employeeRepository;
        this.entitlementService = entitlementService;
    }

    /**
     * Every employee at any depth below {@code employeeId} in the managerId chain (direct
     * reports, their reports, etc) - never includes {@code employeeId} itself. Empty for a leaf
     * employee with no reports - that's correct, not a bug (see #getVisibleEmployeeScope).
     * Backed by EmployeeRepository#findAllSubordinateIds's recursive CTE - see its javadoc for
     * why that's safe with respect to tenant isolation despite being native SQL.
     */
    @Transactional(readOnly = true)
    public Set<UUID> getAllSubordinateIds(UUID employeeId) {
        return new HashSet<>(employeeRepository.findAllSubordinateIds(employeeId));
    }

    /**
     * Shared scoping helper for the Team Leave Calendar and HR dashboard (plan B.4/B.5): an
     * ADMIN's scope is every active employee in the org; anyone else's scope is just their own
     * (possibly empty) subordinate chain via {@link #getAllSubordinateIds}. An individual
     * contributor with no reports correctly gets an empty scope - same "empty state, not a bug"
     * philosophy already established for e.g. leave.LeaveRequestService#listPendingMyApproval.
     */
    @Transactional(readOnly = true)
    public Set<UUID> getVisibleEmployeeScope(UUID viewerId, boolean viewerIsAdmin) {
        if (viewerIsAdmin) {
            return employeeRepository.findByActive(true).stream()
                    .map(Employee::getId)
                    .collect(Collectors.toSet());
        }
        return getAllSubordinateIds(viewerId);
    }

    /**
     * Team-visibility scope for Lead/Visit/Report queries (the TEAM_VISIBILITY entitlement) -
     * distinct from {@link #getVisibleEmployeeScope}, which is unconditional and only feeds the
     * already entitlement-gated Leave/HR endpoints. Here the underlying Lead/Visit/Report
     * endpoints are always reachable; the entitlement only decides whether a manager's *result
     * set* expands beyond their own records. Returns empty both when the org hasn't licensed
     * TEAM_VISIBILITY and when the viewer simply has no reports - callers that need to tell
     * those apart don't need to today (both mean "fall back to self-only"), but this keeps the
     * entitlement check and the hierarchy lookup in one place rather than duplicated per caller.
     */
    @Transactional(readOnly = true)
    public Set<UUID> getTeamVisibilityScope(UUID organizationId, UUID viewerId) {
        if (!entitlementService.isEntitled(organizationId, FeatureEntitlement.TEAM_VISIBILITY)) {
            return Set.of();
        }
        return getAllSubordinateIds(viewerId);
    }
}
