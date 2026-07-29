package com.salesmanager.crm.invoicing;

import java.util.UUID;

/**
 * Optional query filters for {@code GET /invoices}, as requested by the caller. The
 * EMPLOYEE-vs-ADMIN/TEAM_VISIBILITY ownership visibility rule (an EMPLOYEE's ownerId filter is
 * only honored within their own visible scope) is enforced in InvoiceService#list, not here -
 * this record only carries what was literally asked for. Mirrors lead.LeadFilter's shape.
 */
public record InvoiceFilter(InvoiceStatus status, UUID ownerId) {
}
