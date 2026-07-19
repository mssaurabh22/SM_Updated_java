package com.salesmanager.crm.leave;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping - no manual "WHERE organizationId = ..." here.
 */
public interface EmployeeLeaveBalanceRepository extends JpaRepository<EmployeeLeaveBalance, UUID> {

    Optional<EmployeeLeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYear(UUID employeeId, UUID leaveTypeId, int year);
}
