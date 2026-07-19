package com.salesmanager.crm.visit;

/**
 * Kept in sync with the CHECK constraint on visits.visit_type in
 * V4__visits_and_notifications.sql. Deliberately a plain Java enum, not a master_data
 * reference: unlike Industry/City/Product/etc., this is a fixed structural concept (same
 * category as LeadStatus/Role), not admin-customizable business data.
 */
public enum VisitType {
    FIELD,
    TELEPHONIC
}
