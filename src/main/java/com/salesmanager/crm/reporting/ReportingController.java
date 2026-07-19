package com.salesmanager.crm.reporting;

import com.salesmanager.crm.reporting.dto.ConversionRateResponse;
import com.salesmanager.crm.reporting.dto.PipelineSummaryResponse;
import com.salesmanager.crm.reporting.dto.VisitsCompletedVsMissedResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - all aggregation lives in ReportingService, same layering as
 * MasterDataController/LeadController. Every endpoint here is ADMIN-only: unlike Leads/Visits
 * (which have an EMPLOYEE-owner-scoped visibility rule), these are inherently org-wide
 * management analytics, so the class-level @PreAuthorize applies uniformly with no
 * per-endpoint exceptions.
 */
@RestController
@RequestMapping("/reports")
@PreAuthorize("hasRole('ADMIN')")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/pipeline-summary")
    public PipelineSummaryResponse pipelineSummary() {
        return reportingService.pipelineSummary();
    }

    @GetMapping("/conversion-rate")
    public ConversionRateResponse conversionRate() {
        return reportingService.conversionRate();
    }

    @GetMapping("/visits-completed-vs-missed")
    public VisitsCompletedVsMissedResponse visitsCompletedVsMissed(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return reportingService.visitsCompletedVsMissed(dateFrom, dateTo);
    }
}
