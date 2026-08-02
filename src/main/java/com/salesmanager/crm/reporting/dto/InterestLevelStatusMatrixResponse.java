package com.salesmanager.crm.reporting.dto;

import java.util.List;

/** GET /reports/interest-level-status-matrix - one row per interest level (Hot/Warm/Cold/Not
 * Set), sorted Hot -> Warm -> Cold -> Not Set (see ReportingService#interestLevelStatusMatrix). */
public record InterestLevelStatusMatrixResponse(List<InterestLevelStatusRow> rows) {
}
