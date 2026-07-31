package com.salesmanager.crm.calendar;

import java.util.UUID;

/** Published by VisitService#create/#update right after saveAndFlush - see FollowUpScheduledEvent
 * for the identical precedent this mirrors. CalendarSyncEventListener reacts by creating/updating
 * the visit's owner's calendar event, if CALENDAR_SYNC is entitled and they have a connection. */
public record VisitCalendarSyncEvent(UUID visitId, UUID organizationId) {
}
