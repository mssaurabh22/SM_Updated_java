package com.salesmanager.crm.leave.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record LeaveTypeCreateRequest(
        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "code is required")
        String code,

        @NotNull(message = "defaultAllocationDays is required")
        @DecimalMin(value = "0.0", message = "defaultAllocationDays must not be negative")
        BigDecimal defaultAllocationDays,

        Integer sortOrder) {
}
