package com.salesmanager.crm.visit;

import com.salesmanager.crm.activity.ActivityLogService;
import com.salesmanager.crm.activity.ActivityType;
import com.salesmanager.crm.common.event.FollowUpScheduledEvent;
import com.salesmanager.crm.common.event.LeadCreatedEvent;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.security.TenantSessionManager;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to LeadCreatedEvent/FollowUpScheduledEvent by creating stub Visits. Runs at
 * {@code AFTER_COMMIT}, i.e. strictly AFTER the original request's transaction (the single
 * shared transaction TenantFilter opens around the whole request) has already committed.
 *
 * THE CRITICAL GOTCHA (see the Phase 3 spec for the full writeup): by the time an
 * AFTER_COMMIT listener runs, the original transaction is done, so the Postgres session
 * variable {@code app.current_org} (which was SET LOCAL-scoped to that now-finished
 * transaction) is gone - SET LOCAL never survives a transaction boundary, even on the same
 * connection/session. Each method here is itself {@code @Transactional}, so Spring opens a
 * BRAND NEW physical transaction for it BEFORE the method body runs; calling
 * {@code tenantSessionManager.activateTenant(...)} as the very first statement inside that
 * new transaction both (a) re-enables the Hibernate {@code tenantFilter} on this session and
 * (b) re-issues {@code SET LOCAL app.current_org} scoped to THIS new transaction - which the
 * subsequent repository calls below then join (default REQUIRED propagation), satisfying the
 * {@code tenant_isolation_visits}/{@code tenant_isolation_leads} RLS policies. Skipping this
 * (or calling activateTenant before the transaction is open) would make the INSERT below
 * silently fail the RLS check - not a stack trace, just a rejected write - since
 * salesmanager_app is NOBYPASSRLS. This is the exact technique AuthService already uses for
 * its own out-of-normal-request-flow persistence (registration/login).
 *
 * No {@code noRollbackFor} is needed here (unlike LeadService/VisitService) - there is no
 * expected-exception-through-a-shared-transaction problem in a listener's own fresh
 * transaction.
 */
@Component
public class LeadVisitEventListener {

    private final VisitRepository visitRepository;
    private final LeadRepository leadRepository;
    private final TenantSessionManager tenantSessionManager;
    private final ActivityLogService activityLogService;

    public LeadVisitEventListener(VisitRepository visitRepository,
                                   LeadRepository leadRepository,
                                   TenantSessionManager tenantSessionManager,
                                   ActivityLogService activityLogService) {
        this.visitRepository = visitRepository;
        this.leadRepository = leadRepository;
        this.tenantSessionManager = tenantSessionManager;
        this.activityLogService = activityLogService;
    }

    /**
     * logAsVisitToday=true: the touchpoint that led to creating this Lead is logged as a
     * completed Visit dated today (FIELD is a reasonable default type). logAsVisitToday=false
     * (or omitted): no-op.
     */
    // REQUIRES_NEW (not the default REQUIRED) is mandatory here, not stylistic: Spring
    // explicitly rejects a plain @Transactional on an AFTER_COMMIT @TransactionalEventListener
    // ("must not be annotated with @Transactional unless declared as REQUIRES_NEW or
    // NOT_SUPPORTED") precisely because the original transaction is already gone by this point
    // - there is nothing for a default-propagation method to join.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLeadCreated(LeadCreatedEvent event) {
        if (!event.logAsVisitToday()) {
            return;
        }
        try {
            tenantSessionManager.activateTenant(event.organizationId());
            Lead lead = leadRepository.findById(event.leadId()).orElse(null);
            if (lead == null) {
                // Defensive only - the Lead's own insert committed just before this listener
                // fired, so it should always exist.
                return;
            }
            Visit visit = Visit.builder()
                    .leadId(event.leadId())
                    .visitDate(LocalDate.now())
                    .visitType(VisitType.FIELD)
                    .status(VisitStatus.COMPLETED)
                    .createdBy(lead.getOwnerId())
                    .build();
            // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
            visitRepository.saveAndFlush(visit);
            // Same already-reactivated tenant context as the Visit insert just above - no
            // separate activateTenant call needed. actorId is null: this is a system-generated
            // entry (the stub Visit auto-created alongside the Lead), not a manual log-visit
            // action, distinguished from VisitService#create's VISIT_LOGGED entries by both the
            // null actor and this description wording.
            activityLogService.record(lead.getId(), lead.getOwnerId(), lead.getCompanyName(),
                    ActivityType.VISIT_LOGGED, null, "Visit auto-scheduled");
        } finally {
            tenantSessionManager.clearTenant();
        }
    }

    /**
     * Creates a MINIMAL stub follow-up Visit - never a clone of the triggering Lead's/
     * Visit's other fields (no remarks/objections/budget carried over, since those belong to
     * the actual future interaction, which hasn't happened yet).
     */
    // REQUIRES_NEW (not the default REQUIRED) is mandatory here, not stylistic: Spring
    // explicitly rejects a plain @Transactional on an AFTER_COMMIT @TransactionalEventListener
    // ("must not be annotated with @Transactional unless declared as REQUIRES_NEW or
    // NOT_SUPPORTED") precisely because the original transaction is already gone by this point
    // - there is nothing for a default-propagation method to join.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFollowUpScheduled(FollowUpScheduledEvent event) {
        try {
            tenantSessionManager.activateTenant(event.organizationId());
            Lead lead = leadRepository.findById(event.leadId()).orElse(null);
            if (lead == null) {
                return;
            }
            Visit visit = Visit.builder()
                    .leadId(event.leadId())
                    .visitDate(event.followUpDate())
                    .visitType(VisitType.FIELD)
                    .status(VisitStatus.PLANNED)
                    .purposeId(event.carriedOverPurposeId())
                    .createdBy(lead.getOwnerId())
                    .build();
            // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
            visitRepository.saveAndFlush(visit);
            // Same already-reactivated tenant context as the Visit insert just above - see
            // onLeadCreated's identical comment for the null-actor rationale.
            activityLogService.record(lead.getId(), lead.getOwnerId(), lead.getCompanyName(),
                    ActivityType.VISIT_LOGGED, null, "Visit auto-scheduled");
        } finally {
            tenantSessionManager.clearTenant();
        }
    }
}
