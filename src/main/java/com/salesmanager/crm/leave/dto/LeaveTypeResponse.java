package com.salesmanager.crm.leave.dto;

import com.salesmanager.crm.leave.LeaveType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LeaveTypeResponse(
        UUID id,
        UUID organizationId,
        String name,
        String code,
        BigDecimal defaultAllocationDays,
        boolean active,
        int sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static LeaveTypeResponse from(LeaveType leaveType) {
        return new LeaveTypeResponse(
                leaveType.getId(),
                leaveType.getOrganizationId(),
                leaveType.getName(),
                leaveType.getCode(),
                leaveType.getDefaultAllocationDays(),
                leaveType.isActive(),
                leaveType.getSortOrder(),
                leaveType.getCreatedAt(),
                leaveType.getUpdatedAt());
    }
}
