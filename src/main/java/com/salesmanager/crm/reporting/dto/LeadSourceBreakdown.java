package com.salesmanager.crm.reporting.dto;

/** One slice of GET /reports/leads-by-source - label is already resolved (master-data label,
 * or "Other" for leads with no leadSourceId set) so the frontend renders it directly. */
public record LeadSourceBreakdown(String label, long count) {
}
