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
    TEAM_VISIBILITY,

    /**
     * Bundles the Product catalog/stock module AND Invoicing as ONE licensed feature (the
     * user's explicit choice - Invoicing is "instead of independent" of Inventory, not a
     * separately gated add-on). Gates every endpoint in the {@code inventory} and
     * {@code invoicing} packages via {@link RequireEntitlement}.
     */
    INVENTORY_MANAGEMENT,

    /**
     * Gates instant Firebase Cloud Messaging push delivery for the Notification Center - the
     * {@code notifications} table/API stay the source of truth regardless of this entitlement,
     * this only controls whether a device also gets pushed an instant alert. Checked
     * programmatically (like TEAM_VISIBILITY) from PushNotificationEventListener, not via
     * {@link RequireEntitlement}, since {@code POST /device-tokens} itself is gated but the
     * decision to actually push is made per-notification, not per-endpoint.
     */
    PUSH_NOTIFICATIONS,

    /**
     * Gates live sync of an employee's own scheduled Visits into their personal Google Calendar
     * or Outlook (Microsoft Graph) calendar - see calendar.CalendarSyncService.
     */
    CALENDAR_SYNC
}
