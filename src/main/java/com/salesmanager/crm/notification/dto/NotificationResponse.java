package com.salesmanager.crm.notification.dto;

import com.salesmanager.crm.notification.Notification;
import com.salesmanager.crm.notification.NotificationType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only projection of a Notification. {@code payload} is returned as the raw JSON string
 * it was stored as - the frontend parses it itself (its shape varies per {@code type}).
 */
public record NotificationResponse(
        UUID id,
        UUID organizationId,
        UUID recipientId,
        NotificationType type,
        String payload,
        boolean read,
        OffsetDateTime createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getOrganizationId(),
                notification.getRecipientId(),
                notification.getType(),
                notification.getPayload(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
