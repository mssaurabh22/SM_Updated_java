package com.salesmanager.crm.activity;

import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
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

    public ActivityLogService(ActivityLogRepository activityLogRepository, CurrentUser currentUser) {
        this.activityLogRepository = activityLogRepository;
        this.currentUser = currentUser;
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
     * Same EMPLOYEE-forced-to-own-leads visibility rule as LeadService#list/
     * VisitService#list: an EMPLOYEE's ownerId filter is silently forced to their own id
     * regardless of what was requested (so they can never see a colleague's leads' activity
     * via query manipulation, even across multiple leads they don't own); ADMIN gets
     * whatever ownerId filter (or none) was requested, honored as-is, org-wide.
     */
    @Transactional(readOnly = true)
    public Page<ActivityLog> list(ActivityFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        UUID ownerId = principal.getRole() == Role.EMPLOYEE ? principal.getEmployeeId() : filter.ownerId();

        Specification<ActivityLog> spec = Specification
                .where(ActivityLogSpecifications.hasLeadId(filter.leadId()))
                .and(ActivityLogSpecifications.hasOwnerId(ownerId))
                .and(ActivityLogSpecifications.hasType(filter.type()));

        return activityLogRepository.findAll(spec, pageable);
    }
}
