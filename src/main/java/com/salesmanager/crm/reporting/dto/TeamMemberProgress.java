package com.salesmanager.crm.reporting.dto;

import com.salesmanager.crm.lead.LeadStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One row of GET /reports/team-progress - a read-only rollup of a single team member's current
 * workload. {@code leadCountsByStatus} always contains every LeadStatus value (zero-seeded),
 * same convention as PipelineSummaryResponse#byStatus. {@code lastActivityAt} is null when this
 * member has no activity_log entries at all yet.
 */
public record TeamMemberProgress(
        UUID employeeId,
        String employeeName,
        Map<LeadStatus, Long> leadCountsByStatus,
        long totalLeads,
        long visitsDueToday,
        long visitsUpcoming,
        OffsetDateTime lastActivityAt) {
}
