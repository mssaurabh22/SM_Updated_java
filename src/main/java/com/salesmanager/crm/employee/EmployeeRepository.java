package com.salesmanager.crm.employee;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByOrganizationIdAndEmail(UUID organizationId, String email);

    /**
     * Backs EmployeeHierarchyService#getVisibleEmployeeScope's ADMIN branch - every active
     * employee in the org (same tenantFilter/RLS scoping as every other derived query here).
     */
    List<Employee> findByActive(boolean active);

    /**
     * Used only by the login flow, where the tenant is not yet known and the lookup must
     * cross tenant boundaries (backed by AuthService's explicit, documented RLS bypass).
     * Phase 0 treats email as effectively unique across the whole system for login purposes.
     */
    Optional<Employee> findByEmail(String email);

    /**
     * Used only by scheduler.MissedVisitJob, from inside its
     * TenantSessionManager#bypassRlsForCrossTenantLookup() window - the Hibernate tenantFilter
     * is never enabled for a scheduled job (there is no per-request TenantContext to enable it
     * with), so this explicit organizationId parameter is what scopes the lookup correctly,
     * one org at a time, to find that org's ADMINs to escalate a missed visit to.
     */
    List<Employee> findByOrganizationIdAndRole(UUID organizationId, Role role);

    /**
     * Backs EmployeeHierarchyService#getAllSubordinateIds (plan B.2): every employee at any
     * depth below {@code employeeId} in the manager_id chain, NOT including employeeId itself.
     * Deliberately native SQL - JPQL/HQL has no recursive-CTE support, so this is the one
     * repository method in the codebase that steps outside the Hibernate {@code tenantFilter}'s
     * HQL-only reach. That's safe here: this still runs through the same salesmanager_app-
     * connected datasource/transaction as every other query in an authenticated request, so
     * Postgres Row-Level Security (driven by the same {@code app.current_org} session variable
     * TenantSessionManager sets) still scopes every row the recursive query touches - both the
     * anchor member and the recursive join read from the real {@code employees} table, which
     * carries {@code FORCE ROW LEVEL SECURITY}, so a cross-tenant manager/subordinate pair can
     * never leak into the result regardless of the employeeId argument's origin.
     */
    @Query(value = "WITH RECURSIVE subordinates AS ("
            + "SELECT id FROM employees WHERE manager_id = :employeeId "
            + "UNION ALL "
            + "SELECT e.id FROM employees e INNER JOIN subordinates s ON e.manager_id = s.id"
            + ") SELECT id FROM subordinates", nativeQuery = true)
    List<UUID> findAllSubordinateIds(@Param("employeeId") UUID employeeId);
}
