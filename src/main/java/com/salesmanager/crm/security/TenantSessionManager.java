package com.salesmanager.crm.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Central place that wires {@link TenantContext} together with the two enforcement
 * layers that back it:
 *  1. The Hibernate {@code tenantFilter} (application-level, in-JVM query filter).
 *  2. Postgres Row-Level Security, driven by the {@code app.current_org} session GUC,
 *     set via {@code SET LOCAL} so it is automatically scoped/reset to the current
 *     transaction (no manual reset needed, no leak across pooled connections).
 *
 * Used by {@link TenantFilter} for normal authenticated requests, and directly by
 * {@code AuthService} for the handful of auth flows that create/read tenant data
 * before (or without) a JWT-derived tenant context existing.
 */
@Component
public class TenantSessionManager {

    private static final String TENANT_FILTER_NAME = "tenantFilter";
    private static final String TENANT_FILTER_PARAM = "tenantId";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Sets the thread-local tenant context, enables the Hibernate filter on the current
     * session, and (when a transaction is active) sets the Postgres session variable that
     * the RLS policy relies on. Safe to call outside a transaction (e.g. read-only requests
     * with open-in-view) - the DB-level SET LOCAL step is skipped in that case since there is
     * nothing to scope it to.
     */
    public void activateTenant(UUID tenantId) {
        TenantContext.setCurrentTenant(tenantId);

        Session session = entityManager.unwrap(Session.class);
        session.enableFilter(TENANT_FILTER_NAME).setParameter(TENANT_FILTER_PARAM, tenantId);

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // UUID.toString() is safe to interpolate here: it never originates from raw
            // user input, only from a parsed java.util.UUID (TenantContext / JWT claims).
            // Postgres's SET command does not support bind parameters, hence the string build.
            entityManager.createNativeQuery(
                            "SET LOCAL app.current_org = '" + tenantId + "'")
                    .executeUpdate();
        }
    }

    /**
     * Clears the thread-local tenant context. Must be called in a {@code finally} block
     * by every caller of {@link #activateTenant(UUID)} to avoid leaking tenant state across
     * pooled request-handling threads. The DB-side SET LOCAL naturally expires at
     * transaction commit/rollback, so nothing further is required there.
     */
    public void clearTenant() {
        TenantContext.clear();
    }

    /**
     * Narrow, explicitly-audited escape hatch used ONLY by the login flow, where the
     * organization is not yet known (the client supplies only an email/password - not a
     * subdomain or org id) but we must look up the matching Employee row across ALL
     * tenants. Sets a transaction-scoped Postgres session flag that the RLS policy on
     * `employees` treats as a bypass. Never derived from client input beyond the fact
     * that "a login attempt is happening"; never exposed outside AuthService.
     */
    public void bypassRlsForCrossTenantLookup() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.createNativeQuery("SET LOCAL app.bypass_rls = 'on'").executeUpdate();
        }
    }

    /** Turns the bypass flag back off within the same transaction, as soon as it's no longer needed. */
    public void endBypass() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.createNativeQuery("SET LOCAL app.bypass_rls = 'off'").executeUpdate();
        }
    }
}
