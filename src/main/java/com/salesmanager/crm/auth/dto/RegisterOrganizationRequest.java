package com.salesmanager.crm.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterOrganizationRequest(
        @NotBlank(message = "organizationName is required")
        String organizationName,

        @NotBlank(message = "subdomain is required")
        @Pattern(regexp = "^[a-z0-9-]+$", message = "subdomain must be lowercase alphanumeric with hyphens only")
        String subdomain,

        @NotBlank(message = "adminFullName is required")
        String adminFullName,

        @NotBlank(message = "adminEmail is required")
        @Email(message = "adminEmail must be a valid email address")
        String adminEmail,

        @NotBlank(message = "adminPassword is required")
        @Size(min = 8, message = "adminPassword must be at least 8 characters")
        String adminPassword) {
}
