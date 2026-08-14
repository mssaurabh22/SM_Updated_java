package com.salesmanager.crm.reporting.dto;

import java.math.BigDecimal;

/** Backs the Invoices Dashboard's stat-card row (section 17.4 of the Quotations/Invoices plan).
 * "Pending Invoices" = unpaid invoices created within the selected period; "Pending Payments" =
 * unpaid AND already past due (overdue), all-time; "Outstanding" = every unpaid invoice ever,
 * not scoped to the period - see ReportingService#quotationInvoiceSummary. */
public record QuotationInvoiceSummaryResponse(
        long totalQuotations,
        long approvedQuotations,
        long convertedToInvoice,
        long pendingInvoicesCount,
        BigDecimal pendingInvoicesAmount,
        long pendingPaymentsCount,
        BigDecimal pendingPaymentsAmount,
        BigDecimal monthlyBilling,
        BigDecimal outstanding) {
}
