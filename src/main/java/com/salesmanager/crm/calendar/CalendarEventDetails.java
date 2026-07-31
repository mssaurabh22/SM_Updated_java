package com.salesmanager.crm.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Provider-agnostic shape CalendarSyncService builds from a Visit+Lead, handed to whichever of
 * GoogleCalendarClient/OutlookCalendarClient matches the employee's connected provider - keeps
 * the "how do I turn a Visit into an event" logic in exactly one place, not duplicated per
 * provider. {@code allDay} is true when the Visit has no {@code scheduledTime} (a date-only
 * visit); otherwise start/end are a timed slot with a fixed 1-hour default duration - Visit has
 * no explicit duration field of its own (a reasonable v1 default, see the plan's Section 15).
 */
public record CalendarEventDetails(
        String title,
        String location,
        String description,
        boolean allDay,
        LocalDate date,
        LocalDateTime start,
        LocalDateTime end) {
}
