package com.salesmanager.crm.reporting.dto;

import com.salesmanager.crm.lead.LeadStatus;
import java.util.List;
import java.util.Map;

/**
 * Response for GET /reports/pipeline-summary. {@code byStatus} always contains every
 * {@link LeadStatus} value as a key (even ones with zero leads in this org) so the frontend
 * can render a consistent set of categories without special-casing missing keys - see
 * ReportingService#pipelineSummary. {@code byOwner} lists only employees who actually own at
 * least one lead.
 */
public record PipelineSummaryResponse(
        Map<LeadStatus, Long> byStatus,
        long totalLeads,
        List<OwnerBreakdown> byOwner) {
}
