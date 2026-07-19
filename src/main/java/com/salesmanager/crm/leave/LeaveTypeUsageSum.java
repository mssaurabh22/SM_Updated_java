package com.salesmanager.crm.leave;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Spring Data interface projection backing
 * LeaveRequestRepository#sumApprovedDaysByLeaveTypeForEmployees - one row per leaveTypeId with
 * at least one APPROVED request in the queried scope/year (same projection style as
 * lead.LeadStatusCount/lead.LeadOwnerCount).
 */
public interface LeaveTypeUsageSum {

    UUID getLeaveTypeId();

    BigDecimal getTotalDays();
}
