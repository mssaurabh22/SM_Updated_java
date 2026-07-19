package com.salesmanager.crm.security;

import java.util.UUID;

/**
 * ThreadLocal holder for the current request's tenant (organization) id.
 * Populated by {@link TenantFilter} after JWT authentication, and consumed by
 * {@code TenantAware#assignTenantOnPersist()}. Must always be cleared in a
 * {@code finally} block by whoever sets it, since request-handling threads are pooled.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void setCurrentTenant(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
