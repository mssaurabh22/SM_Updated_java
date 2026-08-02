package com.salesmanager.crm.reporting.dto;

import com.salesmanager.crm.visit.VisitType;
import java.util.Map;

/** GET /reports/visits-by-type - byType always contains both FIELD and TELEPHONIC (pre-seeded
 * to zero, same "consistent categories regardless of what's empty" convention as
 * PipelineSummaryResponse#byStatus). */
public record VisitsByTypeResponse(Map<VisitType, Long> byType, long total) {
}
