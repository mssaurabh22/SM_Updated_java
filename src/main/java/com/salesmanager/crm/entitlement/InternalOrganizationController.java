package com.salesmanager.crm.entitlement;

import com.salesmanager.crm.entitlement.dto.InternalOrganizationSummaryResponse;
import com.salesmanager.crm.masterdata.MasterDataSeedService;
import com.salesmanager.crm.tenant.Organization;
import com.salesmanager.crm.tenant.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the Platform Console (a standalone, non-tenant-scoped screen for whoever operates the
 * platform to see every organization and its entitlement state at a glance, instead of hand-
 * crafting curl calls against {@link InternalEntitlementController}). Same shared-secret-key
 * auth pattern as that controller - deliberately not a new auth mechanism, see its javadoc for
 * the full rationale. {@code organizations} has no RLS (it's the tenant root table, not tenant-
 * scoped data), so {@link OrganizationRepository#findAll()} here is a plain, correct cross-org
 * listing with no bypass needed.
 */
@RestController
@RequestMapping("/internal/organizations")
public class InternalOrganizationController {

    private static final String PLATFORM_KEY_HEADER = "X-Platform-Key";

    private final OrganizationRepository organizationRepository;
    private final EntitlementService entitlementService;
    private final MasterDataSeedService masterDataSeedService;

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    public InternalOrganizationController(OrganizationRepository organizationRepository,
                                           EntitlementService entitlementService,
                                           MasterDataSeedService masterDataSeedService) {
        this.organizationRepository = organizationRepository;
        this.entitlementService = entitlementService;
        this.masterDataSeedService = masterDataSeedService;
    }

    @GetMapping
    public List<InternalOrganizationSummaryResponse> list(
            @RequestHeader(value = PLATFORM_KEY_HEADER, required = false) String platformKey) {
        requirePlatformKey(platformKey);
        return organizationRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Backfills the standard master-data catalog (states/cities/industries/etc - see
     * MasterDataSeedService) for an org that predates it or otherwise never received it.
     * Additive only - never touches rows an admin already entered by hand. Not safely
     * re-runnable if seeding already fully or partially succeeded for this org (the underlying
     * seed pass has no duplicate-code guard, unlike the normal admin-facing Masters CRUD) - this
     * is a one-off backfill tool for a genuine gap, not a general idempotent operation.
     */
    @PostMapping("/{orgId}/seed-master-data")
    public void seedMasterData(@PathVariable UUID orgId,
                                @RequestHeader(value = PLATFORM_KEY_HEADER, required = false) String platformKey) {
        requirePlatformKey(platformKey);
        masterDataSeedService.seedDefaultsForOrganization(orgId);
    }

    private InternalOrganizationSummaryResponse toSummary(Organization organization) {
        List<FeatureEntitlement> active = entitlementService.listActiveCodes(organization.getId())
                .stream()
                .sorted()
                .toList();
        return InternalOrganizationSummaryResponse.from(organization, active);
    }

    private void requirePlatformKey(String providedKey) {
        if (providedKey == null || !providedKey.equals(platformAdminKey)) {
            throw new BadCredentialsException("Missing or invalid X-Platform-Key header");
        }
    }
}
