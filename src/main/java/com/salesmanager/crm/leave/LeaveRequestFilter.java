package com.salesmanager.crm.leave;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Optional query filters for the ADMIN-only {@code GET /leave-requests} "All Requests" view, as
 * requested by the caller - same "just carries what was literally asked for" shape as
 * activity.ActivityFilter/lead.LeadFilter.
 */
public record LeaveRequestFilter(
        UUID employeeId,
        LeaveRequestStatus status,
        UUID leaveTypeId,
        LocalDate startDateFrom,
        LocalDate startDateTo) {
}
