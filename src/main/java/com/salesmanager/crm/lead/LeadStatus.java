package com.salesmanager.crm.lead;

/**
 * Kept in sync with the CHECK constraint on leads.status in V3__leads.sql - any change here
 * must be accompanied by a new migration updating that constraint.
 */
public enum LeadStatus {
    NEW,
    CONTACTED,
    NEGOTIATION,
    LOST,
    CLOSED_WON,
    LAPSED
}
