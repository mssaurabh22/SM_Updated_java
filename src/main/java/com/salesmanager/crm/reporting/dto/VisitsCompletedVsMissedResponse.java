package com.salesmanager.crm.reporting.dto;

/**
 * Response for GET /reports/visits-completed-vs-missed. {@code completionRatePercent} is
 * {@code completed / (completed + missed) * 100}, rounded to 2 decimal places by
 * ReportingService#visitsCompletedVsMissed ({@code 0.0} when that denominator is 0). PLANNED
 * visits are reported but deliberately excluded from the rate itself - they haven't resolved
 * to either outcome yet.
 */
public record VisitsCompletedVsMissedResponse(
        long completed,
        long missed,
        long planned,
        double completionRatePercent) {
}
