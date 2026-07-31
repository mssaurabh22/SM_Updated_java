package com.salesmanager.crm.notification;

import com.salesmanager.crm.entitlement.EntitlementService;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.security.TenantSessionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to NotificationCreatedEvent by pushing to the recipient's registered devices, if
 * PUSH_NOTIFICATIONS is entitled for the org. Runs at AFTER_COMMIT - same rationale as
 * visit.LeadVisitEventListener's class javadoc (the notification row must actually be durably
 * committed before anyone is told about it), including the SAME TenantSessionManager activate/
 * clear dance and REQUIRES_NEW propagation, even though this listener only performs READS, not
 * writes: Postgres RLS blocks a SELECT just as completely as an INSERT/UPDATE when
 * {@code app.current_org} isn't set for the current transaction - DeviceTokenRepository
 * #findByEmployeeId silently returns zero rows (not an error) without it, which is exactly the
 * bug this class shipped with initially until a real end-to-end test caught it. "No writes"
 * does not mean "no tenant context needed" - it means no different transaction PROPAGATION is
 * required beyond what reading tenant-scoped data already demands.
 */
@Component
public class PushNotificationEventListener {

    private final EntitlementService entitlementService;
    private final PushNotificationService pushNotificationService;
    private final TenantSessionManager tenantSessionManager;

    public PushNotificationEventListener(EntitlementService entitlementService,
                                          PushNotificationService pushNotificationService,
                                          TenantSessionManager tenantSessionManager) {
        this.entitlementService = entitlementService;
        this.pushNotificationService = pushNotificationService;
        this.tenantSessionManager = tenantSessionManager;
    }

    // REQUIRES_NEW (not the default REQUIRED) - mandatory, not stylistic, same reason
    // LeadVisitEventListener's identical methods give: Spring rejects a plain @Transactional on
    // an AFTER_COMMIT listener, since the original transaction is already gone by this point.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            tenantSessionManager.activateTenant(event.organizationId());
            if (!entitlementService.isEntitled(event.organizationId(), FeatureEntitlement.PUSH_NOTIFICATIONS)) {
                return;
            }
            pushNotificationService.sendToEmployee(
                    event.recipientId(), event.notificationId(), event.type(), event.payload());
        } finally {
            tenantSessionManager.clearTenant();
        }
    }
}
