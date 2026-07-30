package com.salesmanager.crm.reporting.dto;

import java.util.List;

/** GET /reports/leads-by-source - sorted by count descending (see ReportingService#leadsBySource). */
public record LeadsBySourceResponse(List<LeadSourceBreakdown> bySource) {
}
