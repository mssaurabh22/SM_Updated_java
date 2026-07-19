package com.salesmanager.crm.employeeactivity;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * A NEW, separate history table from the Lead-centric {@code activity.ActivityLog} - that
 * table's schema is {@code lead_id NOT NULL} and Leave/Attendance events have no associated
 * Lead at all, so forcing a nullable leadId onto it to accommodate a genuinely different domain
 * would blur what that table means (see the Employee Entitlement plan, Part B.3). This table
 * follows the exact same design (ActivityLogService's pattern), just scoped to
 * {@code employeeId} - whose history this belongs to, the requesting employee, NOT the
 * approver - instead of {@code leadId}.
 *
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate {@code tenantFilter} WHERE clause is guaranteed to apply,
 * regardless of Hibernate version quirks around filter inheritance - same pattern as every
 * other entity in this codebase.
 *
 * {@code actorId} is the employee who performed the action - for SUBMITTED/CANCELLED this is
 * the same as {@code employeeId} (the requester acting on their own request); for
 * APPROVED/REJECTED it's the approver (the resolved manager) or an ADMIN acting as
 * override/fallback, which may differ from {@code employeeId}.
 */
@Entity
@Table(name = "employee_activity_log")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class EmployeeActivityLog extends TenantAware {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmployeeActivityType type;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String description;
}
