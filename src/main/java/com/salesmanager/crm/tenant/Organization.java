package com.salesmanager.crm.tenant;

import com.salesmanager.crm.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The tenant itself. Deliberately does NOT extend {@link TenantAware} - an organization
 * has no organization_id, it IS the organization.
 */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Organization extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String subdomain;

    /**
     * Raw JSON stored as a String in a jsonb column. Deliberately not mapped to a rich
     * type in Phase 0 - not needed yet, and avoids pulling in a JSON <-> Java converter.
     * {@code @JdbcTypeCode(SqlTypes.JSON)} tells Hibernate 6 to bind this as jsonb rather
     * than plain varchar, which Postgres otherwise rejects with a type mismatch.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "theme_settings", columnDefinition = "jsonb")
    private String themeSettings;
}
