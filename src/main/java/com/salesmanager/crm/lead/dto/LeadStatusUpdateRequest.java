package com.salesmanager.crm.lead.dto;

import com.salesmanager.crm.lead.LeadStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body for PATCH /leads/{id}/status - the ONE place lostReasonId/lostReasonOther are actually
 * set (per the frontend's LeadLostReasonDialog). Exactly one of lostReasonId/lostReasonOther is
 * only required (enforced in LeadService#updateStatus, not here via Bean Validation, since the
 * requirement is conditional on status) when status == LOST; both are ignored for every other
 * status value. Supplying both at once is rejected as a 400, same as every other creatable
 * field pair - see MasterDataService#validateCreatableField.
 */
public record LeadStatusUpdateRequest(
        @NotNull(message = "status is required")
        LeadStatus status,

        UUID lostReasonId,

        @Size(max = 255, message = "lostReasonOther must be at most 255 characters")
        String lostReasonOther) {
}
