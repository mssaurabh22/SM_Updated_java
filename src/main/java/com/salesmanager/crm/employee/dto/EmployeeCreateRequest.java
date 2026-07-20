package com.salesmanager.crm.employee.dto;

import com.salesmanager.crm.employee.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record EmployeeCreateRequest(
        @NotBlank(message = "fullName is required")
        String fullName,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        String email,

        @Pattern(regexp = "^\\d{10}$", message = "phone must be exactly 10 digits")
        String phone,

        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        String password,

        @NotNull(message = "role is required")
        Role role,

        UUID designationId,

        UUID cityId,

        UUID stateId,

        Set<UUID> assignedProductIds,

        UUID managerId) {
}
