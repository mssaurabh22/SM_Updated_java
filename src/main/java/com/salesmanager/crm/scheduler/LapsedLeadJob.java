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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 4: once nightly, flips Leads whose {@code next_followup_date} has passed and whose
 * status isn't already a terminal one (LOST/CLOSED_WON/LAPSED - a lead already resolved, or
 * already LAPSED, is never resurrected into LAPSED again) to LAPSED, and notifies just that
 * lead's owner - a personal follow-up reminder, unlike MissedVisitJob's org-wide ADMIN
 * escalation, per the spec.
 *
 * Later addition: after that per-owner loop, also sends every ADMIN in each affected org a
 * single once-per-run LEAD_LAPSED_DIGEST notification summarizing how many of that org's
 * leads lapsed in this run - one notification per Admin per org per run, not one per lapsed
 * lead, so an Admin managing a team gets visibility without the noise of a duplicate
 * per-lead ping for every lead their reports own.
 *
 * Same no-HTTP-request/no-TenantContext/no-Hibernate-filter situation as MissedVisitJob - see
 * its class javadoc for why this uses a native, set-based UPDATE...RETURNING under
 * {@link TenantSessionManager#bypassRlsForCrossTenantLookup()} instead of a per-tenant loop.
 */
@Component
public class LapsedLeadJob {

    private static final Logger log = LoggerFactory.getLogger(LapsedLeadJob.class);

    // Distinct from MissedVisitJob's two lock keys - this job has exactly one @Scheduled method.
    private static final long LAPSE_SWEEP_LOCK_KEY = 913_020_001L;

    private final EntityManager entityManager;
    private final TenantSessionManager tenantSessionManager;
    private final EmployeeRepository employeeRepository;
    private final LeadRepository leadRepository;
    private final NotificationService notificationService;
    private final ActivityLogService activityLogService;
    private final AdvisoryLockRunner advisoryLockRunner;
    private final ObjectMapper objectMapper;

    public LapsedLeadJob(EntityManager entityManager,
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

    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void flagLapsedLeads() {
        advisoryLockRunner.runExclusive(entityManager, LAPSE_SWEEP_LOCK_KEY, "flagLapsedLeads", this::doFlagLapsedLeads);
    }

    @SuppressWarnings("unchecked")
    private void doFlagLapsedLeads() {
        try {
            tenantSessionManager.bypassRlsForCrossTenantLookup();
            List<Object[]> rows = entityManager.createNativeQuery(
                            "UPDATE leads SET status = 'LAPSED' "
                                    + "WHERE next_followup_date < CURRENT_DATE "
                                    + "AND status NOT IN ('LOST', 'CLOSED_WON', 'LAPSED') "
                                    + "RETURNING id, organization_id, owner_id")
                    .getResultList();
            if (rows.isEmpty()) {
                return;
            }
            log.info("Flagged {} lead(s) as LAPSED", rows.size());

            Map<UUID, Integer> lapsedCountByOrg = new HashMap<>();
            for (Object[] row : rows) {
                UUID leadId = (UUID) row[0];
                UUID organizationId = (UUID) row[1];
                UUID ownerId = (UUID) row[2];

                String payload = buildPayload(leadId);
                // Loaded here (same RLS-bypass window already active for this whole method) just
                // for companyName - ownerId is already available from the RETURNING row above.
                String companyName = leadRepository.findById(leadId).map(Lead::getCompanyName).orElse(null);
                // See MissedVisitJob#processMissedVisits for why TenantContext must be set
                // (and cleared) around each notification/activity-log-creation call in a
                // scheduled job.
                try {
                    TenantContext.setCurrentTenant(organizationId);
                    notificationService.create(ownerId, NotificationType.LEAD_LAPSED, payload);
                    if (companyName != null) {
                        activityLogService.record(leadId, ownerId, companyName, ActivityType.LEAD_LAPSED, null,
                                "Lead auto-flagged as lapsed");
                    }
                } finally {
                    TenantContext.clear();
                }

                lapsedCountByOrg.merge(organizationId, 1, Integer::sum);
            }

            // One digest per Admin per org per run, summarizing this run's whole batch for that
            // org - NOT a second per-lead notification to Admins (see class javadoc).
            for (Map.Entry<UUID, Integer> entry : lapsedCountByOrg.entrySet()) {
                UUID organizationId = entry.getKey();
                int count = entry.getValue();

                List<Employee> admins = employeeRepository.findByOrganizationIdAndRole(organizationId, Role.ADMIN);
                if (admins.isEmpty()) {
                    continue;
                }

                String digestPayload = buildDigestPayload(count);
                try {
                    TenantContext.setCurrentTenant(organizationId);
                    for (Employee admin : admins) {
                        notificationService.create(admin.getId(), NotificationType.LEAD_LAPSED_DIGEST, digestPayload);
                    }
                } finally {
                    TenantContext.clear();
                }
            }
        } finally {
            tenantSessionManager.endBypass();
        }
    }

    /** Small hand-built JSON payload for the LEAD_LAPSED notification, same style as
     *  LeadService#buildReassignmentPayload for LEAD_REASSIGNED. */
    private String buildPayload(UUID leadId) {
        try {
            return objectMapper.writeValueAsString(Map.of("leadId", leadId.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize LEAD_LAPSED notification payload", e);
        }
    }

    /** Small hand-built JSON payload for the LEAD_LAPSED_DIGEST notification - deliberately just
     *  a count, not every lapsed lead's id/company name, per the class javadoc's "lightweight
     *  nudge, not a detailed report" intent. */
    private String buildDigestPayload(int count) {
        try {
            return objectMapper.writeValueAsString(Map.of("count", count));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize LEAD_LAPSED_DIGEST notification payload", e);
        }
    }
}
