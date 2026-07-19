package com.salesmanager.crm.leave.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * All fields required/updatable - this is also how an admin reactivates a previously-
 * deactivated leave type (PUT with active=true), same convention as
 * masterdata.MasterDataUpdateRequest. {@code code} is deliberately absent - immutable after
 * creation, same as MasterData.
 */
public record LeaveTypeUpdateRequest(
        @NotBlank(message = "name is required")
        String name,

        @NotNull(message = "defaultAllocationDays is required")
        @DecimalMin(value = "0.0", message = "defaultAllocationDays must not be negative")
        BigDecimal defaultAllocationDays,

        @NotNull(message = "sortOrder is required")
        Integer sortOrder,

        @NotNull(message = "active is required")
        Boolean active) {
}
