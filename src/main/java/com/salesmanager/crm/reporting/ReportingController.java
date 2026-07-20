package com.salesmanager.crm.reporting;

import com.salesmanager.crm.reporting.dto.ConversionRateResponse;
import com.salesmanager.crm.reporting.dto.PipelineSummaryResponse;
import com.salesmanager.crm.reporting.dto.VisitsCompletedVsMissedResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - all aggregation lives in ReportingService, same layering as
 * MasterDataController/LeadController. No class-level role restriction: an ADMIN always gets
 * the unrestricted org-wide picture, while an EMPLOYEE is let through here but then scoped (or
 * flatly denied with a 403) by ReportingService#resolveOwnerScope based on the TEAM_VISIBILITY
 * entitlement and whether they actually have any subordinates - see that method's comment.
 */
@RestController
@RequestMapping("/reports")
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
