package com.salesmanager.crm.leave;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * Company holiday calendar - admin-configurable, used by leave.LeaveRequestService to exclude
 * non-working days from a leave request's totalDays computation. No "active" concept (unlike
 * LeaveType/MasterData's soft-delete) - a holiday either exists for a given date or it doesn't;
 * removing one is a plain hard delete (see HolidayService#delete).
 *
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate {@code tenantFilter} WHERE clause is guaranteed to apply,
 * regardless of Hibernate version quirks around filter inheritance - same pattern as every
 * other entity in this codebase.
 */
@Entity
@Table(name = "holidays")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Holiday extends TenantAware {

    @Column(nullable = false)
    private String name;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;
}
