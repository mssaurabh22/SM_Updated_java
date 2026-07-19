package com.salesmanager.crm.visit.dto;

import com.salesmanager.crm.visit.VisitStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Body for PATCH /visits/{id}/status. PLANNED<->COMPLETED transitions freely (including
 * MISSED->COMPLETED, e.g. "it turned out the visit did happen after all"); a client-supplied
 * MISSED is rejected with 400 by VisitService#updateStatus - only a future scheduled job may
 * set that.
 */
public record VisitStatusUpdateRequest(
        @NotNull(message = "status is required")
        VisitStatus status) {
}
