package com.salesmanager.crm.visit;

/**
 * Kept in sync with the CHECK constraint on visits.status in
 * V4__visits_and_notifications.sql. MISSED is deliberately never settable directly by a
 * client (VisitService rejects it on create/updateStatus with 400) - only a future
 * scheduled job is meant to set it, once one exists.
 */
public enum VisitStatus {
    PLANNED,
    COMPLETED,
    MISSED
}
