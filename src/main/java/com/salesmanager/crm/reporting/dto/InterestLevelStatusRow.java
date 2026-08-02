package com.salesmanager.crm.reporting.dto;

import com.salesmanager.crm.lead.LeadStatus;
import java.util.Map;

/** One row of GET /reports/interest-level-status-matrix - interestLevel is already resolved to
 * a master-data label ("Hot"/"Warm"/"Cold"), or "Not Set" for leads with no interest level
 * recorded yet. byStatus always contains every LeadStatus value (pre-seeded to zero), same
 * "consistent categories" convention as PipelineSummaryResponse#byStatus. */
public record InterestLevelStatusRow(String interestLevel, Map<LeadStatus, Long> byStatus, long total) {
}
