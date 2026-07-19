package com.salesmanager.crm.entitlement;

import com.salesmanager.crm.entitlement.dto.GrantRevokeRequest;
import com.salesmanager.crm.entitlement.dto.OrganizationEntitlementResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-operator-only grant/revoke endpoint - deliberately OUTSIDE the normal JWT/
 * TenantContext flow entirely (see {@link OrganizationEntitlement}'s javadoc): there is no
 * "current org" to derive from a token here, since the org is a path parameter supplied by
 * whoever operates the platform, not an org's own authenticated user. Routed around Spring
 * Security auth via SecurityConfig's {@code /internal/**} permitAll() matcher; authorization
 * is instead a single shared-secret header check against {@code platform.admin.key}
 * ({@code PLATFORM_ADMIN_KEY} env var, dev-only fallback in application.yml - same pattern as
 * {@code jwt.secret}). This is deliberately not a new filter/interceptor/auth mechanism, just
 * an inline header comparison, per the plan's "keep it simple" instruction: this is a
 * low-frequency, low-volume operation (the SaaS vendor's own team granting a feature to a
 * handful of customer orgs), not something that warrants a whole second authentication system.
 *
 * A missing/incorrect key throws {@link BadCredentialsException}, reusing
 * GlobalExceptionHandler's existing 401 mapping for it rather than adding a new exception type.
 */
@RestController
@RequestMapping("/internal/organizations/{orgId}/entitlements")
public class InternalEntitlementController {

    private static final String PLATFORM_KEY_HEADER = "X-Platform-Key";

    private final EntitlementService entitlementService;

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    public InternalEntitlementController(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @PatchMapping("/{code}")
    public OrganizationEntitlementResponse grantOrRevoke(
            @PathVariable UUID orgId,
            @PathVariable FeatureEntitlement code,
            @RequestBody GrantRevokeRequest request,
            @RequestHeader(value = PLATFORM_KEY_HEADER, required = false) String platformKey) {
        requirePlatformKey(platformKey);
        if (request.action() == GrantRevokeRequest.Action.GRANT) {
            return OrganizationEntitlementResponse.from(
                    entitlementService.grant(orgId, code, request.expiresAt(), request.grantedBy()));
        }
        entitlementService.revoke(orgId, code);
        return entitlementService.find(orgId, code)
                .map(OrganizationEntitlementResponse::from)
                .orElse(null);
    }

    /** Lists everything currently granted (active AND revoked/expired) for this org - simple oversight/debugging value. */
    @GetMapping
    public List<OrganizationEntitlementResponse> list(
            @PathVariable UUID orgId,
            @RequestHeader(value = PLATFORM_KEY_HEADER, required = false) String platformKey) {
        requirePlatformKey(platformKey);
        return entitlementService.listAll(orgId).stream()
                .map(OrganizationEntitlementResponse::from)
                .toList();
    }

    private void requirePlatformKey(String providedKey) {
        if (providedKey == null || !providedKey.equals(platformAdminKey)) {
            throw new BadCredentialsException("Missing or invalid X-Platform-Key header");
        }
    }
}
