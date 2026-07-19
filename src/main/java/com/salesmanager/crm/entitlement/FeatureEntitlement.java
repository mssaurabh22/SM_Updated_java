package com.salesmanager.crm.entitlement;

/**
 * The platform's catalog of licensable features - kept as a Java enum, not a DB table, per
 * the plan's YAGNI rationale: with exactly one gated feature so far, admin-editable catalog
 * data would be speculative. Add a real {@code entitlements} table only once there are enough
 * distinct codes to justify one (same discipline as why {@code NEXT_ACTION} stayed unwired
 * rather than over-building).
 */
public enum FeatureEntitlement {
    EMPLOYEE_LEAVE_MANAGEMENT
}
