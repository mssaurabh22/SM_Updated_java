package com.salesmanager.crm.entitlement.dto;

import java.time.OffsetDateTime;

/**
 * Body for {@code PATCH /internal/organizations/{orgId}/entitlements/{code}}. {@code expiresAt}
 * and {@code grantedBy} are optional and only meaningful for a {@link Action#GRANT} action -
 * an unlimited/anonymous grant simply omits them.
 */
public record GrantRevokeRequest(Action action, OffsetDateTime expiresAt, String grantedBy) {

    public enum Action {
        GRANT,
        REVOKE
    }
}
