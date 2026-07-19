package com.salesmanager.crm.reporting.dto;

/**
 * Response for GET /reports/conversion-rate. {@code conversionRatePercent} is
 * {@code closedWonCount / totalLeads * 100}, rounded to 2 decimal places by
 * ReportingService#conversionRate ({@code 0.0} when totalLeads is 0, never a division by
 * zero).
 */
public record ConversionRateResponse(
        long totalLeads,
        long closedWonCount,
        long lostCount,
        double conversionRatePercent) {
}
