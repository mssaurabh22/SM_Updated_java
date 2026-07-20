package com.salesmanager.crm.notification.dto;

/** Backs GET /notifications/unread-count - a lightweight badge-count endpoint. */
public record UnreadCountResponse(long count) {
}
