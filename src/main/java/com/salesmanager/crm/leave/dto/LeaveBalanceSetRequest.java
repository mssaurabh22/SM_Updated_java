package com.salesmanager.crm.leave.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Body for the ADMIN-only PUT /leave-balances/employee/{employeeId}/leave-type/{leaveTypeId}. */
public record LeaveBalanceSetRequest(
        @NotNull(message = "allocatedDays is required")
        @DecimalMin(value = "0.0", message = "allocatedDays must not be negative")
        BigDecimal allocatedDays,

        @DecimalMin(value = "0.0", message = "carriedForwardDays must not be negative")
        BigDecimal carriedForwardDays) {
}
