package com.salesmanager.crm.hrdashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row per active LeaveType (plan B.5's leave-utilization breakdown). averageDaysUsed is a
 * true scope-wide average - (SUM of APPROVED total_days for this type/year across the whole
 * scope) / (count of employees in scope), including employees who used zero days of this type
 * in the denominator, not just the count of people who happened to use it. 0 for every type when
 * the scope is empty (an individual contributor with no reports), never a divide-by-zero.
 */
public record LeaveUtilizationResponse(UUID leaveTypeId, String leaveTypeName, BigDecimal averageDaysUsed) {
}
