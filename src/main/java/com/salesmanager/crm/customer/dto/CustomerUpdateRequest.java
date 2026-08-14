package com.salesmanager.crm.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CustomerUpdateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 255, message = "contactPerson must be at most 255 characters")
        String contactPerson,

        @Size(max = 20, message = "phone must be at most 20 characters")
        String phone,

        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @Size(max = 1000, message = "address must be at most 1000 characters")
        String address,

        UUID cityId,

        UUID stateId,

        UUID industryId,

        @Size(max = 20, message = "gstin must be at most 20 characters")
        String gstin,

        @Size(max = 2000, message = "notes must be at most 2000 characters")
        String notes,

        @NotNull(message = "active is required")
        Boolean active) {
}
