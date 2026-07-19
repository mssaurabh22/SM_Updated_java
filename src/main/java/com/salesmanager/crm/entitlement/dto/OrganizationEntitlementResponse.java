package com.salesmanager.crm.entitlement.dto;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.OrganizationEntitlement;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only projection of an OrganizationEntitlement row - used by the internal grant/revoke/
 * list endpoints. {@code active} is computed the same way EntitlementService#isEntitled
 * computes it (not revoked, not expired), included here purely for the internal oversight
 * GET's convenience so callers don't have to re-derive it from grantedAt/expiresAt/revokedAt.
 */
public record OrganizationEntitlementResponse(
        UUID id,
        UUID organizationId,
        FeatureEntitlement entitlementCode,
        OffsetDateTime grantedAt,
        OffsetDateTime expiresAt,
        String grantedBy,
        OffsetDateTime revokedAt,
        boolean active) {

    public static OrganizationEntitlementResponse from(OrganizationEntitlement entitlement) {
        boolean active = entitlement.getRevokedAt() == null
                && (entitlement.getExpiresAt() == null || entitlement.getExpiresAt().isAfter(OffsetDateTime.now()));
        return new OrganizationEntitlementResponse(
                entitlement.getId(),
                entitlement.getOrganizationId(),
                entitlement.getEntitlementCode(),
                entitlement.getGrantedAt(),
                entitlement.getExpiresAt(),
                entitlement.getGrantedBy(),
                entitlement.getRevokedAt(),
                active);
    }
}
