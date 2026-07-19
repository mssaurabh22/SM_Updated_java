package com.salesmanager.crm.employee;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
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
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate `tenantFilter` WHERE clause is guaranteed to apply to this
 * entity's queries regardless of Hibernate version quirks around filter inheritance.
 */
@Entity
@Table(name = "employees")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Employee extends TenantAware {

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * References a master_data row of type DESIGNATION. Not a JPA relation - just the raw
     * id - since the shared master_data table can't be mapped as a single typed entity
     * association per Employee field; MasterDataService#validateReference enforces the
     * type match at the service layer instead.
     */
    @Column(name = "designation_id")
    private UUID designationId;

    /** References a master_data row of type CITY - see designationId javadoc above. */
    @Column(name = "city_id")
    private UUID cityId;

    /** References a master_data row of type STATE - see designationId javadoc above. */
    @Column(name = "state_id")
    private UUID stateId;

    /**
     * Self-referential - the employee's direct manager, another Employee in the same org.
     * Nullable: not every employee has one. Used to route leave-request approvals
     * (leave.LeaveRequestService) to the direct manager, with any ADMIN as a fallback/override.
     */
    @Column(name = "manager_id")
    private UUID managerId;

    /**
     * References master_data rows of type PRODUCT. Deliberately a simple id set (not a
     * full relational mapping to MasterData) - Phase 1 only needs to store/report the ids;
     * the frontend fetches product labels separately via its own master-data dropdown.
     */
    @ElementCollection
    @CollectionTable(name = "employee_products", joinColumns = @JoinColumn(name = "employee_id"))
    @Column(name = "product_id")
    @Builder.Default
    private Set<UUID> assignedProductIds = new HashSet<>();

    /**
     * Phase 6: per-employee override of the org's theme branding (see
     * Organization#themeSettings). Same raw-JSON-in-jsonb pattern as that field - a plain
     * String Java-side, bound as real Postgres jsonb via {@code @JdbcTypeCode(SqlTypes.JSON)}
     * rather than the text/varchar mismatch documented in V4's notifications.payload comment.
     * Parsed/merged into a {@code ThemeSettings} at the service layer (theme.ThemeService),
     * where each field may be left null to mean "inherit the org default for this field".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "theme_preference", columnDefinition = "jsonb")
    private String themePreference;
}
