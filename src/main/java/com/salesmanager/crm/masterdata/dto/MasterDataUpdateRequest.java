package com.salesmanager.crm.masterdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * All three fields are required/updatable - this is also how an admin reactivates a
 * previously-deactivated entry (PUT with active=true), not just how they deactivate one.
 * {@code code} is deliberately absent - it is immutable after creation.
 */
public record MasterDataUpdateRequest(
        @NotBlank(message = "label is required")
        String label,

        @NotNull(message = "sortOrder is required")
        Integer sortOrder,

        @NotNull(message = "active is required")
        Boolean active) {
}
