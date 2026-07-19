package com.salesmanager.crm.masterdata;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One generic table backing all 10 dropdown-driving reference-data types (see
 * {@link MasterType}), rather than 10 near-identical tables. The concrete {@code @Filter}
 * annotation is repeated here (not just inherited from {@link TenantAware}) for the same
 * reason as Employee - Hibernate filter inheritance from a mapped superclass alone is not
 * reliable across versions.
 */
@Entity
@Table(name = "master_data")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MasterData extends TenantAware {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MasterType type;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false)
    private String label;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "parent_id")
    private UUID parentId;

    /**
     * Raw JSON stored as a String, same pattern as Organization#themeSettings -
     * {@code @JdbcTypeCode(SqlTypes.JSON)} tells Hibernate 6 to bind this as jsonb.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;
}
