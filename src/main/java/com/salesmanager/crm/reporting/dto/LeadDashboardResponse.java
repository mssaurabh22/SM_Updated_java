package com.salesmanager.crm.reporting.dto;

import java.util.List;

/**
 * Composite response for the Reports Dashboard's global-filter-driven block (section 17.5 of the
 * Quotations/Invoices plan) - every stat card/chart/table below is computed from the SAME
 * filtered Lead fetch (see ReportingService#leadDashboard's javadoc for why this is one Java
 * aggregation pass rather than N separately-filtered SQL queries).
 */
public record LeadDashboardResponse(
        long totalLeads,
        long hotLeads,
        long warmLeads,
        long coldLeads,
        long notSetInterestCount,
        long todayFollowUpCount,
        long overdueFollowUpCount,
        long closedWonCount,
        long closedLostCount,
        double conversionRatePercent,
        ExpectedClosures expectedClosures,
        List<LabelCount> byBusinessType,
        List<LabelCount> byProduct,
        List<CompanySummaryRow> companyWiseSummary,
        List<CitySummaryRow> cityWiseSummary,
        List<ProductPerformanceRow> productPerformance,
        List<InterestLevelPerformanceRow> interestLevelPerformance,
        List<EmployeePerformanceRow> employeePerformance,
        FollowUpSummary followUpSummary) {

    public record LabelCount(String label, long count) {
    }

    public record CompanySummaryRow(String company, long total, long hot, long won, long lost) {
    }

    public record CitySummaryRow(String city, long total, long hot, long won) {
    }

    public record ProductPerformanceRow(String product, long total, long won, double conversionRatePercent) {
    }

    public record InterestLevelPerformanceRow(String interestLevel, long total, long won,
                                               double conversionRatePercent) {
    }

    /** followUpPending = has a nextFollowupDate set and isn't already LOST/CLOSED_WON. */
    public record EmployeePerformanceRow(String employeeName, long total, long hot, long followUpPending, long won,
                                          double conversionRatePercent) {
    }

    /** Counts of leads whose expectedCloseDate falls in each bucket - no monetary total, since
     * Lead has no clean numeric "deal value" field (turnover is the CUSTOMER's turnover, not
     * this deal's size; budgetRange is free text). */
    public record ExpectedClosures(long thisWeek, long thisMonth, long nextMonth) {
    }

    public record FollowUpSummary(long todayCount, long tomorrowCount, long next7DaysCount, long overdueCount,
                                   long unassignedCount) {
    }
}
