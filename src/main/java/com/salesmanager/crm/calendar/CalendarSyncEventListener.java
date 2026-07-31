package com.salesmanager.crm.calendar;

import com.salesmanager.crm.entitlement.EntitlementService;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.security.TenantSessionManager;
import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to VisitCalendarSyncEvent - same AFTER_COMMIT + TenantSessionManager activate/clear
 * dance as visit.LeadVisitEventListener (see its class javadoc for the full "why" writeup).
 * Resolves the visit's effective owner via its parent Lead (Visit has no ownerId of its own,
 * same pattern used everywhere else in this codebase - see VisitService#loadForCurrentUser's
 * javadoc), and only calls CalendarSyncService if CALENDAR_SYNC is entitled AND that owner has
 * an active calendar_connections row - both are common, expected "nothing to do" cases, not
 * errors.
 */
@Component
public class CalendarSyncEventListener {

    private final VisitRepository visitRepository;
    private final LeadRepository leadRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final EntitlementService entitlementService;
    private final CalendarSyncService calendarSyncService;
    private final TenantSessionManager tenantSessionManager;

    public CalendarSyncEventListener(VisitRepository visitRepository,
                                      LeadRepository leadRepository,
                                      CalendarConnectionRepository calendarConnectionRepository,
                                      EntitlementService entitlementService,
                                      CalendarSyncService calendarSyncService,
                                      TenantSessionManager tenantSessionManager) {
        this.visitRepository = visitRepository;
        this.leadRepository = leadRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.entitlementService = entitlementService;
        this.calendarSyncService = calendarSyncService;
        this.tenantSessionManager = tenantSessionManager;
    }

    // REQUIRES_NEW (not the default REQUIRED) - mandatory, not stylistic, same reason
    // LeadVisitEventListener's identical methods give: Spring rejects a plain @Transactional on
    // an AFTER_COMMIT listener, since the original transaction is already gone by this point.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisitCalendarSync(VisitCalendarSyncEvent event) {
        if (!entitlementService.isEntitled(event.organizationId(), FeatureEntitlement.CALENDAR_SYNC)) {
            return;
        }
        try {
            tenantSessionManager.activateTenant(event.organizationId());
            Visit visit = visitRepository.findById(event.visitId()).orElse(null);
            if (visit == null) {
                return;
            }
            Lead lead = leadRepository.findById(visit.getLeadId()).orElse(null);
            if (lead == null) {
                return;
            }
            CalendarConnection connection = calendarConnectionRepository
                    .findByEmployeeId(lead.getOwnerId()).orElse(null);
            if (connection == null) {
                return;
            }
            calendarSyncService.upsertEvent(visit, lead, connection);
        } finally {
            tenantSessionManager.clearTenant();
        }
    }
}
