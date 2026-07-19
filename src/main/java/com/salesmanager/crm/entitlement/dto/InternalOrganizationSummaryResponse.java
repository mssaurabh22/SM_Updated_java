package com.salesmanager.crm.entitlement.dto;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.tenant.Organization;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Backs the Platform Console's org list (GET /internal/organizations): one row per organization
 * in the whole platform, with its currently-active entitlement codes inlined so the console can
 * render grant state without an extra per-org lookup.
 */
public record InternalOrganizationSummaryResponse(
        UUID id,
        String name,
        String subdomain,
        OffsetDateTime createdAt,
        List<FeatureEntitlement> activeEntitlementCodes) {

    public static InternalOrganizationSummaryResponse from(Organization organization,
                                                            List<FeatureEntitlement> activeEntitlementCodes) {
        return new InternalOrganizationSummaryResponse(
                organization.getId(),
                organization.getName(),
                organization.getSubdomain(),
                organization.getCreatedAt(),
                activeEntitlementCodes);
    }
}
