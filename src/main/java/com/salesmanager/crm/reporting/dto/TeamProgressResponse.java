package com.salesmanager.crm.reporting.dto;

import java.util.List;

/** Response for GET /reports/team-progress, sorted by employeeName. */
public record TeamProgressResponse(List<TeamMemberProgress> members) {
}
