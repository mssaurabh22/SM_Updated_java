package com.salesmanager.crm.invoicing;

/** Kept in sync with the CHECK constraint on invoices.status in V12__invoicing.sql. Just
 * UNPAID/PAID for v1 - no PARTIALLY_PAID/CANCELLED yet (Invoice is create-only in v1, no
 * void/cancel path). */
public enum InvoiceStatus {
    UNPAID,
    PAID
}
