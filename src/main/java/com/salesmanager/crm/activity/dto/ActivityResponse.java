package com.salesmanager.crm.activity.dto;

import com.salesmanager.crm.activity.ActivityLog;
import com.salesmanager.crm.activity.ActivityType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only projection of an ActivityLog entry. {@code actorId} is null for system-generated
 * entries; the frontend resolves it to a display name (and humanizes {@code type}/dates) for
 * display, same as everywhere else in this app - {@code description} is deliberately just a
 * short, already-templated string built at write time, not re-derived here.
 */
public record ActivityResponse(
        UUID id,
        UUID leadId,
        UUID ownerId,
        String companyName,
        ActivityType type,
        UUID actorId,
        String description,
        OffsetDateTime createdAt) {

    public static ActivityResponse from(ActivityLog activityLog) {
        return new ActivityResponse(
                activityLog.getId(),
                activityLog.getLeadId(),
                activityLog.getOwnerId(),
                activityLog.getCompanyName(),
                activityLog.getType(),
                activityLog.getActorId(),
                activityLog.getDescription(),
                activityLog.getCreatedAt());
    }
}
