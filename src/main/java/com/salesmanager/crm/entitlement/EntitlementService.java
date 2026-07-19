package com.salesmanager.crm.entitlement;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates organization_entitlements. Deliberately takes {@code organizationId} as an
 * explicit parameter on every method rather than reading it from TenantContext - this table
 * is NOT tenant-scoped data in the usual sense (see {@link OrganizationEntitlement}'s class
 * javadoc), so callers each decide how to obtain an orgId rather than this service assuming
 * one particular source: the internal grant/revoke controller's "current org" is a path
 * parameter with no JWT/TenantContext behind it at all, while {@link RequireEntitlement}'s
 * aspect resolves the org from TenantContext for normal authenticated requests.
 */
@Service
public class EntitlementService {

    private final OrganizationEntitlementRepository repository;

    public EntitlementService(OrganizationEntitlementRepository repository) {
        this.repository = repository;
    }

    /**
     * Upsert, not always-insert: if a row already exists for {@code (organizationId, code)} -
     * even a previously-revoked one - it is updated in place (revokedAt cleared, grantedAt/
     * expiresAt/grantedBy refreshed) rather than inserting a duplicate, which would violate
     * the {@code (organization_id, entitlement_code)} unique constraint.
     */
    @Transactional
    public OrganizationEntitlement grant(UUID organizationId, FeatureEntitlement code,
                                          OffsetDateTime expiresAt, String grantedBy) {
        OrganizationEntitlement entitlement = repository
                .findByOrganizationIdAndEntitlementCode(organizationId, code)
                .orElseGet(() -> OrganizationEntitlement.builder()
                        .organizationId(organizationId)
                        .entitlementCode(code)
                        .build());
        entitlement.setGrantedAt(OffsetDateTime.now());
        entitlement.setExpiresAt(expiresAt);
        entitlement.setGrantedBy(grantedBy);
        entitlement.setRevokedAt(null);
        // saveAndFlush (not save) - see EmployeeService#create's comment: @CreationTimestamp/
        // @UpdateTimestamp only populate in-memory on an actual flush, not at save()/persist().
        return repository.saveAndFlush(entitlement);
    }

    /** No-op (not an error) if there is nothing to revoke for {@code (organizationId, code)}. */
    @Transactional
    public void revoke(UUID organizationId, FeatureEntitlement code) {
        repository.findByOrganizationIdAndEntitlementCode(organizationId, code)
                .ifPresent(entitlement -> {
                    entitlement.setRevokedAt(OffsetDateTime.now());
                    repository.saveAndFlush(entitlement);
                });
    }

    /** The single {@code (organizationId, code)} row, whatever its active/revoked/expired state. */
    @Transactional(readOnly = true)
    public Optional<OrganizationEntitlement> find(UUID organizationId, FeatureEntitlement code) {
        return repository.findByOrganizationIdAndEntitlementCode(organizationId, code);
    }

    /** True only if a row exists, is not revoked, and (if it has an expiry) hasn't passed it. */
    @Transactional(readOnly = true)
    public boolean isEntitled(UUID organizationId, FeatureEntitlement code) {
        return find(organizationId, code).filter(EntitlementService::isActive).isPresent();
    }

    /** Backs GET /organizations/me/entitlements - the currently-active codes for an org. */
    @Transactional(readOnly = true)
    public Set<FeatureEntitlement> listActiveCodes(UUID organizationId) {
        Set<FeatureEntitlement> active = new HashSet<>();
        for (OrganizationEntitlement entitlement : repository.findByOrganizationId(organizationId)) {
            if (isActive(entitlement)) {
                active.add(entitlement.getEntitlementCode());
            }
        }
        return active;
    }

    /** Full grant history (active, revoked, expired) - backs the internal oversight GET endpoint. */
    @Transactional(readOnly = true)
    public List<OrganizationEntitlement> listAll(UUID organizationId) {
        return repository.findByOrganizationId(organizationId);
    }

    private static boolean isActive(OrganizationEntitlement entitlement) {
        if (entitlement.getRevokedAt() != null) {
            return false;
        }
        OffsetDateTime expiresAt = entitlement.getExpiresAt();
        return expiresAt == null || expiresAt.isAfter(OffsetDateTime.now());
    }
}
