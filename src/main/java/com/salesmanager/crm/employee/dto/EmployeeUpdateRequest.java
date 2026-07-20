package com.salesmanager.crm.employee.dto;

import com.salesmanager.crm.employee.Role;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import java.util.UUID;

/**
 * All fields optional/nullable - only fields actually present (non-null) are applied by
 * EmployeeService#update. Email is deliberately NOT included here: changing login email is
 * a bigger workflow (verification, uniqueness re-check, etc.), out of scope for Phase 1.
 */
public record EmployeeUpdateRequest(
        String fullName,

        @Pattern(regexp = "^\\d{10}$", message = "phone must be exactly 10 digits")
        String phone,

        UUID designationId,

        UUID cityId,

        UUID stateId,

        Set<UUID> assignedProductIds,

        Role role,

        UUID managerId) {
}
