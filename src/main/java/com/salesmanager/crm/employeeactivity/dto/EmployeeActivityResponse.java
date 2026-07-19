package com.salesmanager.crm.employeeactivity.dto;

import com.salesmanager.crm.employeeactivity.EmployeeActivityLog;
import com.salesmanager.crm.employeeactivity.EmployeeActivityType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only projection of an EmployeeActivityLog entry. {@code actorId} is set for every entry
 * (unlike activity.ActivityResponse's system-generated nulls - every Leave lifecycle event here
 * is a human action); the frontend resolves it to a display name for presentation, same
 * "backend returns ids, frontend resolves names" split used throughout this app.
 */
public record EmployeeActivityResponse(
        UUID id,
        UUID employeeId,
        EmployeeActivityType type,
        UUID actorId,
        String description,
        OffsetDateTime createdAt) {

    public static EmployeeActivityResponse from(EmployeeActivityLog employeeActivityLog) {
        return new EmployeeActivityResponse(
                employeeActivityLog.getId(),
                employeeActivityLog.getEmployeeId(),
                employeeActivityLog.getType(),
                employeeActivityLog.getActorId(),
                employeeActivityLog.getDescription(),
                employeeActivityLog.getCreatedAt());
    }
}
