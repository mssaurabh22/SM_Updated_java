package com.salesmanager.crm.lead.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body for PATCH /leads/{id}/reassign (ADMIN only). newOwnerId must reference a real, active
 * Employee in the same organization - enforced in LeadService#reassign, not here.
 */
public record LeadReassignRequest(
        @NotNull(message = "newOwnerId is required")
        UUID newOwnerId) {
}
