package com.salesmanager.crm.leave.dto;

import jakarta.validation.constraints.Size;

/** Body for PATCH /leave-requests/{id}/approve and /reject - decisionNote is optional on both. */
public record LeaveRequestDecisionRequest(
        @Size(max = 500, message = "decisionNote must be at most 500 characters")
        String decisionNote) {
}
