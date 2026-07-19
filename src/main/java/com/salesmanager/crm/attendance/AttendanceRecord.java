package com.salesmanager.crm.attendance;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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
 * One row per (employee, calendar day) that the employee actually clocked in on - a
 * Weekend/Holiday/Absent day usually has no row at all (see AttendanceService's derivation
 * rule); only a day with at least a check-in gets one. {@code status} (Present/Absent/On Leave/
 * Holiday/Weekend) is deliberately NOT a column here - always derived at read time by
 * AttendanceService, never stored (same "compute, don't denormalize" discipline as
 * leave.EmployeeLeaveBalance's allocated-only storage).
 *
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate {@code tenantFilter} WHERE clause is guaranteed to apply,
 * regardless of Hibernate version quirks around filter inheritance - same pattern as every
 * other entity in this codebase.
 */
@Entity
@Table(name = "attendance_records")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AttendanceRecord extends TenantAware {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "check_in_at")
    private OffsetDateTime checkInAt;

    @Column(name = "check_out_at")
    private OffsetDateTime checkOutAt;
}
