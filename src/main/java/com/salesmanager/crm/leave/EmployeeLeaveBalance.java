package com.salesmanager.crm.leave;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * Stores ONLY the allocation (admin-set, defaults from LeaveType#defaultAllocationDays,
 * adjustable per-employee e.g. for a mid-year joiner) - "used"/"remaining" are NEVER stored
 * here, always computed at read time as SUM(LeaveRequest.totalDays) WHERE status=APPROVED for
 * this employee+leaveType+year (see EmployeeLeaveBalanceService#getBalanceSummary). Storing a
 * running "used" counter would drift the moment a request is edited/cancelled/approved after
 * the fact - same discipline already used elsewhere in this codebase for avoiding denormalized
 * state that can go stale.
 *
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate {@code tenantFilter} WHERE clause is guaranteed to apply,
 * regardless of Hibernate version quirks around filter inheritance - same pattern as every
 * other entity in this codebase.
 */
@Entity
@Table(name = "employee_leave_balances")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class EmployeeLeaveBalance extends TenantAware {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(nullable = false)
    private int year;

    @Column(name = "allocated_days", nullable = false)
    private BigDecimal allocatedDays;

    @Column(name = "carried_forward_days", nullable = false)
    @Builder.Default
    private BigDecimal carriedForwardDays = BigDecimal.ZERO;
}
