package com.salesmanager.crm.tenant;

import com.salesmanager.crm.common.BaseEntity;
import com.salesmanager.crm.security.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base for every entity scoped to a single organization (tenant). {@code organizationId}
 * is NEVER settable from a DTO/client input - it is populated exclusively from
 * {@link TenantContext} in {@link #assignTenantOnPersist()}, immediately before insert,
 * and is not updatable afterwards.
 *
 * The {@code tenantFilter} Hibernate filter definition lives here so it can be declared
 * once; concrete entities (e.g. Employee) still need their own {@code @Filter} annotation
 * for the WHERE clause to actually apply, since Hibernate's {@code @Filter} does not always
 * reliably inherit its SQL condition from a {@code @MappedSuperclass} alone.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
public abstract class TenantAware extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @PrePersist
    protected void assignTenantOnPersist() {
        if (this.organizationId == null) {
            UUID currentTenant = TenantContext.getCurrentTenant();
            if (currentTenant == null) {
                throw new IllegalStateException(
                        "Cannot persist a tenant-scoped entity without an active TenantContext");
            }
            this.organizationId = currentTenant;
        }
    }
}
