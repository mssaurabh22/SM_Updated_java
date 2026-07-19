package com.salesmanager.crm.leave;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * {@code approverId} is the resolved approver SNAPSHOTTED at submission time (the requester's
 * {@code Employee.managerId} as of that moment, or null if they had none - a null approverId
 * request is one only an ADMIN can act on, since there's no manager to fall back from).
 * {@code decidedById} is whoever ACTUALLY approved/rejected it - could be the resolved manager,
 * or an ADMIN acting as override/fallback - these can differ, which is exactly why both columns
 * exist (see leave.LeaveRequestService#approve/#reject).
 *
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate {@code tenantFilter} WHERE clause is guaranteed to apply,
 * regardless of Hibernate version quirks around filter inheritance - same pattern as every
 * other entity in this codebase.
 */
@Entity
@Table(name = "leave_requests")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeaveRequest extends TenantAware {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "total_days", nullable = false)
    private BigDecimal totalDays;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveRequestStatus status;

    @Column(name = "approver_id")
    private UUID approverId;

    @Column(name = "decided_by_id")
    private UUID decidedById;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;
}
