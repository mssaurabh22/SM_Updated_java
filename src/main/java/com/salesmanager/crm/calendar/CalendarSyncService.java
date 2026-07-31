package com.salesmanager.crm.calendar;

import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a CalendarEventDetails from a Visit+Lead and creates/updates the corresponding event
 * via whichever of GoogleCalendarClient/OutlookCalendarClient matches the connection's provider.
 * Called only from CalendarSyncEventListener (AFTER_COMMIT) - never throws back into a caller,
 * since a calendar sync failure must never affect the Visit save that triggered it (already true
 * structurally, since AFTER_COMMIT means that save has already committed).
 */
@Service
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);
    private static final int DEFAULT_DURATION_MINUTES = 60;

    private final CalendarConnectionService calendarConnectionService;
    private final GoogleCalendarClient googleCalendarClient;
    private final OutlookCalendarClient outlookCalendarClient;
    private final VisitRepository visitRepository;

    public CalendarSyncService(CalendarConnectionService calendarConnectionService,
                                GoogleCalendarClient googleCalendarClient,
                                OutlookCalendarClient outlookCalendarClient,
                                VisitRepository visitRepository) {
        this.calendarConnectionService = calendarConnectionService;
        this.googleCalendarClient = googleCalendarClient;
        this.outlookCalendarClient = outlookCalendarClient;
        this.visitRepository = visitRepository;
    }

    @Transactional
    public void upsertEvent(Visit visit, Lead lead, CalendarConnection connection) {
        CalendarEventDetails details = buildDetails(visit, lead);
        try {
            String accessToken = calendarConnectionService.getValidAccessToken(connection);
            String eventId = visit.getExternalCalendarEventId();
            if (eventId == null) {
                eventId = createEvent(connection, accessToken, details);
            } else {
                updateEvent(connection, accessToken, eventId, details);
            }
            visit.setExternalCalendarEventId(eventId);
            visit.setCalendarSyncStatus("SYNCED");
            visit.setCalendarSyncError(null);
            visitRepository.saveAndFlush(visit);
        } catch (Exception e) {
            log.warn("Calendar sync failed for visit {}: {}", visit.getId(), e.getMessage());
            visit.setCalendarSyncStatus("FAILED");
            visit.setCalendarSyncError(shortMessage(e));
            visitRepository.saveAndFlush(visit);
            calendarConnectionService.recordSyncError(connection.getId(), shortMessage(e));
        }
    }

    private String createEvent(CalendarConnection connection, String accessToken, CalendarEventDetails details) {
        return switch (connection.getProvider()) {
            case GOOGLE -> googleCalendarClient.createEvent(accessToken, connection.getCalendarId(), details);
            case OUTLOOK -> outlookCalendarClient.createEvent(accessToken, details);
        };
    }

    private void updateEvent(CalendarConnection connection, String accessToken, String eventId,
                              CalendarEventDetails details) {
        switch (connection.getProvider()) {
            case GOOGLE -> googleCalendarClient.updateEvent(accessToken, connection.getCalendarId(), eventId, details);
            case OUTLOOK -> outlookCalendarClient.updateEvent(accessToken, eventId, details);
        }
    }

    private CalendarEventDetails buildDetails(Visit visit, Lead lead) {
        String title = "Visit: " + lead.getCompanyName();
        String location = visit.getAddress() != null ? visit.getAddress() : lead.getAddress();
        String description = buildDescription(visit, lead);
        if (visit.getScheduledTime() == null) {
            return new CalendarEventDetails(title, location, description, true, visit.getVisitDate(), null, null);
        }
        LocalDateTime start = LocalDateTime.of(visit.getVisitDate(), visit.getScheduledTime());
        LocalDateTime end = start.plusMinutes(DEFAULT_DURATION_MINUTES);
        return new CalendarEventDetails(title, location, description, false, null, start, end);
    }

    private String buildDescription(Visit visit, Lead lead) {
        StringBuilder sb = new StringBuilder();
        String contactPerson = visit.getContactPerson() != null ? visit.getContactPerson() : lead.getContactPerson();
        String contactNo = visit.getContactNo() != null ? visit.getContactNo() : lead.getContactNo();
        if (contactPerson != null) {
            sb.append("Contact: ").append(contactPerson);
        }
        if (contactNo != null) {
            sb.append(sb.isEmpty() ? "" : "\n").append("Phone: ").append(contactNo);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
