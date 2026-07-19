package com.salesmanager.crm.leave.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code employeeId} is deliberately absent - always derived from CurrentUser in
 * LeaveRequestController/Service, never client-supplied, same golden rule as
 * TenantAware#organizationId.
 */
public record LeaveRequestCreateRequest(
        @NotNull(message = "leaveTypeId is required")
        UUID leaveTypeId,

        @NotNull(message = "startDate is required")
        LocalDate startDate,

        @NotNull(message = "endDate is required")
        LocalDate endDate,

        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason) {
}
