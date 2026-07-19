package com.salesmanager.crm.leave.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One leave type's balance summary for a given employee+year - a computed aggregate, not a
 * direct entity projection (there may be no EmployeeLeaveBalance row persisted at all yet; see
 * EmployeeLeaveBalanceService#getBalanceSummary), same "service builds the response record
 * directly" style as reporting.ReportingService's dto responses. {@code usedDays} is always
 * derived from APPROVED LeaveRequests, never stored.
 */
public record LeaveBalanceResponse(
        UUID leaveTypeId,
        String leaveTypeName,
        BigDecimal allocatedDays,
        BigDecimal carriedForwardDays,
        BigDecimal usedDays,
        BigDecimal remainingDays) {
}
