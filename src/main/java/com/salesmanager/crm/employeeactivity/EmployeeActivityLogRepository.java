package com.salesmanager.crm.employeeactivity;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here.
 */
public interface EmployeeActivityLogRepository extends JpaRepository<EmployeeActivityLog, UUID> {

    Page<EmployeeActivityLog> findByEmployeeId(UUID employeeId, Pageable pageable);

    Page<EmployeeActivityLog> findByEmployeeIdAndType(UUID employeeId, EmployeeActivityType type, Pageable pageable);

    /** Backs an ADMIN's org-wide feed narrowed only by type, with no employeeId filter. */
    Page<EmployeeActivityLog> findByType(EmployeeActivityType type, Pageable pageable);
}
