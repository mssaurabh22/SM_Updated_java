package com.salesmanager.crm.entitlement;

import com.salesmanager.crm.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Deliberately does NOT extend {@link com.salesmanager.crm.tenant.TenantAware} and carries no
 * {@code @Filter} - unlike every other feature table in this app, organization_entitlements is
 * NOT tenant-scoped data that a tenant manages about itself; it's platform-level data ABOUT
 * tenants (which orgs are licensed for which features), so a tenant must never be able to
 * read/write its own row via the ordinary Hibernate tenantFilter/RLS path its own JWT gives it
 * access to. {@code organizationId} here is a plain field, set directly by
 * {@link EntitlementService} from an explicit method parameter - never auto-populated from
 * {@code TenantContext} the way {@code TenantAware#assignTenantOnPersist} works everywhere
 * else. See V8__entitlements.sql's migration comment for the full rationale - this is the
 * plan's one deliberate exception to this codebase's usual tenant-isolation pattern.
 */
@Entity
@Table(name = "organization_entitlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class OrganizationEntitlement extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entitlement_code", nullable = false, length = 50)
    private FeatureEntitlement entitlementCode;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "granted_by")
    private String grantedBy;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
