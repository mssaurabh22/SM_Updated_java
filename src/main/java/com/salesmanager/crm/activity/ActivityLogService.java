package com.salesmanager.crm.activity;

import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and lists activity/journey-timeline entries for Leads. {@link #record} is called
 * directly (synchronously, same-transaction) from LeadService/VisitService's own
 * @Transactional methods at the moments listed on {@link ActivityType} - NOT via an event
 * listener, since these are all pure history records of things that already successfully
 * happened in the same transaction (no "orphan on rollback" risk the way the AFTER_COMMIT
 * stub-Visit creation in visit.LeadVisitEventListener has). The one exception is the
 * auto-stub-Visit case, which naturally piggybacks on that EXISTING AFTER_COMMIT listener
 * since that's when the stub Visit itself is created - see that listener's javadoc.
 */
@Service
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final CurrentUser currentUser;
    private final EmployeeHierarchyService employeeHierarchyService;

    public ActivityLogService(ActivityLogRepository activityLogRepository, CurrentUser currentUser,
                               EmployeeHierarchyService employeeHierarchyService) {
        this.activityLogRepository = activityLogRepository;
        this.currentUser = currentUser;
        this.employeeHierarchyService = employeeHierarchyService;
    }

    // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
    @Transactional
    public ActivityLog record(UUID leadId, UUID ownerId, String companyName, ActivityType type,
                               UUID actorId, String description) {
        ActivityLog activityLog = ActivityLog.builder()
                .leadId(leadId)
                .ownerId(ownerId)
                .companyName(companyName)
                .type(type)
                .actorId(actorId)
                .description(description)
                .build();
        return activityLogRepository.saveAndFlush(activityLog);
    }

    /**
     * Same EMPLOYEE-forced-to-own-leads visibility rule as LeadService#list/VisitService#list,
     * including the same TEAM_VISIBILITY expansion: an EMPLOYEE's ownerId filter is silently
     * forced to their own id regardless of what was requested (so they can never see a
     * colleague's leads' activity via query manipulation), UNLESS TEAM_VISIBILITY is entitled
     * and they have subordinates, in which case their scope is themself + every subordinate at
     * any depth (an out-of-scope ownerId filter is ignored the same way LeadService#list
     * ignores one). ADMIN gets whatever ownerId filter (or none) was requested, honored as-is,
     * org-wide.
     */
    @Transactional(readOnly = true)
    public Page<ActivityLog> list(ActivityFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        Specification<ActivityLog> spec = Specification
                .where(ActivityLogSpecifications.hasLeadId(filter.leadId()))
                .and(ActivityLogSpecifications.hasType(filter.type()));

        if (principal.getRole() == Role.EMPLOYEE) {
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            if (subordinateIds.isEmpty()) {
                spec = spec.and(ActivityLogSpecifications.hasOwnerId(principal.getEmployeeId()));
            } else {
                Set<UUID> teamScope = new HashSet<>(subordinateIds);
                teamScope.add(principal.getEmployeeId());
                if (filter.ownerId() != null && teamScope.contains(filter.ownerId())) {
                    spec = spec.and(ActivityLogSpecifications.hasOwnerId(filter.ownerId()));
                } else {
                    spec = spec.and(ActivityLogSpecifications.hasOwnerIdIn(teamScope));
                }
            }
        } else {
            spec = spec.and(ActivityLogSpecifications.hasOwnerId(filter.ownerId()));
        }

        return activityLogRepository.findAll(spec, pageable);
    }
}
