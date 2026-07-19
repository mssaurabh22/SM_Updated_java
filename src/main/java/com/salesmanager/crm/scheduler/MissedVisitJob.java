package com.salesmanager.crm.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.activity.ActivityLogService;
import com.salesmanager.crm.activity.ActivityType;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.notification.NotificationService;
import com.salesmanager.crm.notification.NotificationType;
import com.salesmanager.crm.security.TenantContext;
import com.salesmanager.crm.security.TenantSessionManager;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 4: flips overdue PLANNED Visits to MISSED, in two deliberately separate sweeps (see
 * each method's javadoc for why), and escalates every transition to every ADMIN in that
 * Visit's org - plus the visit's own owner, derived from its parent Lead's ownerId since
 * Visit has no owner field of its own (same as everywhere else in this codebase) - via a
 * VISIT_MISSED notification.
 *
 * Runs with NO HTTP request, NO {@link TenantContext}, and NO Hibernate {@code tenantFilter}
 * enabled - so the batch UPDATE below is a plain native, set-based SQL statement (scoped by
 * {@link TenantSessionManager#bypassRlsForCrossTenantLookup()}) that intentionally operates
 * across ALL orgs in one round-trip, not a per-tenant loop. This mirrors AuthService's login
 * flow - the only other legitimate use of that same RLS-bypass mechanism - in scope and
 * try/finally discipline.
 */
@Component
public class MissedVisitJob {

    private static final Logger log = LoggerFactory.getLogger(MissedVisitJob.class);

    // Distinct, fixed advisory-lock keys - one per @Scheduled method below - so that a second
    // instance running the SAME sweep concurrently is a no-op rather than double-processing.
    private static final long TIMED_SWEEP_LOCK_KEY = 913_010_001L;
    private static final long UNTIMED_SWEEP_LOCK_KEY = 913_010_002L;

    private static final String RETURNING_COLUMNS = "id, organization_id, lead_id";

    private final EntityManager entityManager;
    private final TenantSessionManager tenantSessionManager;
    private final EmployeeRepository employeeRepository;
    private final LeadRepository leadRepository;
    private final NotificationService notificationService;
    private final ActivityLogService activityLogService;
    private final AdvisoryLockRunner advisoryLockRunner;
    private final ObjectMapper objectMapper;

    public MissedVisitJob(EntityManager entityManager,
                           TenantSessionManager tenantSessionManager,
                           EmployeeRepository employeeRepository,
                           LeadRepository leadRepository,
                           NotificationService notificationService,
                           ActivityLogService activityLogService,
                           AdvisoryLockRunner advisoryLockRunner,
                           ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.tenantSessionManager = tenantSessionManager;
        this.employeeRepository = employeeRepository;
        this.leadRepository = leadRepository;
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
        this.advisoryLockRunner = advisoryLockRunner;
        this.objectMapper = objectMapper;
    }

    /**
     * Every 5 minutes: a PLANNED visit with a specific {@code scheduled_time} is MISSED once
     * its scheduled moment is more than 30 minutes in the past - a short, frequent grace-period
     * check appropriate for a time-of-day-precise appointment.
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void flagMissedTimedVisits() {
        advisoryLockRunner.runExclusive(entityManager, TIMED_SWEEP_LOCK_KEY, "flagMissedTimedVisits", () ->
                processMissedVisits("UPDATE visits SET status = 'MISSED' "
                        + "WHERE status = 'PLANNED' AND scheduled_time IS NOT NULL "
                        + "AND (visit_date + scheduled_time) < (now() - interval '30 minutes') "
                        + "RETURNING " + RETURNING_COLUMNS));
    }

    /**
     * Once nightly at 01:00: a date-only PLANNED visit (no {@code scheduled_time} to compare
     * against) is MISSED only once its whole day has fully ended. Deliberately a SEPARATE,
     * once-a-night sweep from the 5-minute one above - a date-only visit must not flip to
     * MISSED mid-day just because 30 minutes have passed since midnight.
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void flagMissedUntimedVisits() {
        advisoryLockRunner.runExclusive(entityManager, UNTIMED_SWEEP_LOCK_KEY, "flagMissedUntimedVisits", () ->
                processMissedVisits("UPDATE visits SET status = 'MISSED' "
                        + "WHERE status = 'PLANNED' AND scheduled_time IS NULL "
                        + "AND visit_date < CURRENT_DATE "
                        + "RETURNING " + RETURNING_COLUMNS));
    }

    /**
     * Runs the given native UPDATE...RETURNING inside a single RLS-bypass window (covering both
     * the cross-tenant bulk update AND the notification-creation loop that follows it - same
     * scope AuthService uses for its one bypass call), then notifies every ADMIN of each
     * affected org plus that visit's own owner (the parent Lead's ownerId - loaded via a plain
     * findById, fine to do inside this same bypass window since RLS is already bypassed for the
     * whole batch operation). Admins are looked up once per distinct org (not once per visit) to
     * avoid redundant queries when many visits in the same org transition in one sweep. The
     * recipient set for each visit is deduplicated via a {@code Set<UUID>} so an owner who
     * happens to also be an Admin in their org receives exactly one VISIT_MISSED notification,
     * not two.
     */
    @SuppressWarnings("unchecked")
    private void processMissedVisits(String updateReturningSql) {
        try {
            tenantSessionManager.bypassRlsForCrossTenantLookup();
            List<Object[]> rows = entityManager.createNativeQuery(updateReturningSql).getResultList();
            if (rows.isEmpty()) {
                return;
            }
            log.info("Flagged {} visit(s) as MISSED", rows.size());

            Map<UUID, List<Employee>> adminsByOrg = new HashMap<>();
            for (Object[] row : rows) {
                UUID visitId = (UUID) row[0];
                UUID organizationId = (UUID) row[1];
                UUID leadId = (UUID) row[2];

                List<Employee> admins = adminsByOrg.computeIfAbsent(organizationId,
                        orgId -> employeeRepository.findByOrganizationIdAndRole(orgId, Role.ADMIN));

                // Loaded once (not just mapped to ownerId) so it's also available below for the
                // VISIT_MISSED activity-log entry's ownerId/companyName snapshot - relies on the
                // same RLS-bypass window findById already uses here (see EmployeeRepository's
                // identical comment re: findById + bypassRlsForCrossTenantLookup).
                Lead lead = leadRepository.findById(leadId).orElse(null);

                Set<UUID> recipientIds = new HashSet<>();
                for (Employee admin : admins) {
                    recipientIds.add(admin.getId());
                }
                if (lead != null) {
                    recipientIds.add(lead.getOwnerId());
                }
                if (recipientIds.isEmpty()) {
                    continue;
                }

                String payload = buildPayload(visitId, leadId);
                // Notification#assignTenantOnPersist (and ActivityLog#assignTenantOnPersist,
                // identically) requires a TenantContext to stamp organizationId - there is none
                // ambient in a scheduled job, so set it just for this org's notification/
                // activity-log-creation calls, and clear it right after (same "always cleared in
                // a finally" discipline TenantContext's own javadoc demands).
                try {
                    TenantContext.setCurrentTenant(organizationId);
                    for (UUID recipientId : recipientIds) {
                        notificationService.create(recipientId, NotificationType.VISIT_MISSED, payload);
                    }
                    if (lead != null) {
                        activityLogService.record(leadId, lead.getOwnerId(), lead.getCompanyName(),
                                ActivityType.VISIT_MISSED, null, "Visit auto-flagged as missed");
                    }
                } finally {
                    TenantContext.clear();
                }
            }
        } finally {
            tenantSessionManager.endBypass();
        }
    }

    /** Small hand-built JSON payload for the VISIT_MISSED notification, same style as
     *  LeadService#buildReassignmentPayload for LEAD_REASSIGNED. */
    private String buildPayload(UUID visitId, UUID leadId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "visitId", visitId.toString(),
                    "leadId", leadId.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize VISIT_MISSED notification payload", e);
        }
    }
}
