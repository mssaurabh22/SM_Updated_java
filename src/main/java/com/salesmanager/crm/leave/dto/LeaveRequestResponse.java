package com.salesmanager.crm.leave.dto;

import com.salesmanager.crm.leave.LeaveRequest;
import com.salesmanager.crm.leave.LeaveRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only projection of a LeaveRequest. approverId/decidedById/employeeId are returned as raw
 * ids - the frontend resolves them to display names itself, same "backend returns ids, frontend
 * resolves names" split used throughout this app (e.g. activity.ActivityResponse#actorId).
 */
public record LeaveRequestResponse(
        UUID id,
        UUID organizationId,
        UUID employeeId,
        UUID leaveTypeId,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalDays,
        String reason,
        LeaveRequestStatus status,
        UUID approverId,
        UUID decidedById,
        OffsetDateTime decidedAt,
        String decisionNote,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static LeaveRequestResponse from(LeaveRequest leaveRequest) {
        return new LeaveRequestResponse(
                leaveRequest.getId(),
                leaveRequest.getOrganizationId(),
                leaveRequest.getEmployeeId(),
                leaveRequest.getLeaveTypeId(),
                leaveRequest.getStartDate(),
                leaveRequest.getEndDate(),
                leaveRequest.getTotalDays(),
                leaveRequest.getReason(),
                leaveRequest.getStatus(),
                leaveRequest.getApproverId(),
                leaveRequest.getDecidedById(),
                leaveRequest.getDecidedAt(),
                leaveRequest.getDecisionNote(),
                leaveRequest.getCreatedAt(),
                leaveRequest.getUpdatedAt());
    }
}
