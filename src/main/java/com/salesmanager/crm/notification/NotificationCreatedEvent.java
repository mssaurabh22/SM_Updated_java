package com.salesmanager.crm.notification;

import java.util.UUID;

/**
 * Published by NotificationService#create right after every notification row is saved -
 * PushNotificationEventListener reacts by sending an FCM push to the recipient's registered
 * device tokens (if PUSH_NOTIFICATIONS is entitled for the org). {@code type}/{@code payload}
 * are carried through as a DATA-only FCM message (not a pre-rendered "notification" message) so
 * the actual display text is built client-side by the exact same describeNotification() logic
 * the in-app Notification Center already uses - no second copy of the per-type message templates
 * needs to be maintained in Java.
 */
public record NotificationCreatedEvent(UUID notificationId, UUID organizationId, UUID recipientId,
                                        NotificationType type, String payload) {
}
