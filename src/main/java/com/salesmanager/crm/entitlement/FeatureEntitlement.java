package com.salesmanager.crm.entitlement;

/**
 * The platform's catalog of licensable features - kept as a Java enum, not a DB table, per
 * the plan's YAGNI rationale: with only a couple of gated features so far, admin-editable
 * catalog data would be speculative. Add a real {@code entitlements} table only once there are
 * enough distinct codes to justify one (same discipline as why {@code NEXT_ACTION} stayed
 * unwired rather than over-building).
 */
public enum FeatureEntitlement {
    EMPLOYEE_LEAVE_MANAGEMENT,

    /**
     * Expands a manager's (an employee who has at least one subordinate via
     * {@code Employee.managerId}) visibility in Leads/Visits/Reports from "just their own" to
     * "themself + every subordinate at any depth" - see employee.EmployeeHierarchyService
     * #getTeamVisibilityScope. Unlike EMPLOYEE_LEAVE_MANAGEMENT (which gates a whole module's
     * endpoints via @RequireEntitlement), this gates the *result set* of endpoints that are
     * always reachable (GET /leads, GET /visits, GET /reports/*) - so it's checked
     * programmatically via EntitlementService#isEntitled, not through the @RequireEntitlement
     * aspect.
     */
    TEAM_VISIBILITY
}
