package com.salesmanager.crm.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Plain REST calls against Google Calendar API v3 - see CalendarOAuthService's class javadoc
 * for why no provider SDK is used here. */
@Component
public class GoogleCalendarClient {

    private static final String BASE_URL = "https://www.googleapis.com/calendar/v3/calendars";
    private static final String TIME_ZONE = "Asia/Kolkata";

    private final RestClient restClient = CalendarHttpClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String createEvent(String accessToken, String calendarId, CalendarEventDetails details) {
        JsonNode response = restClient.post()
                .uri(BASE_URL + "/{calendarId}/events", calendarId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .body(buildEventBody(details))
                .retrieve()
                .body(JsonNode.class);
        return response.get("id").asText();
    }

    public void updateEvent(String accessToken, String calendarId, String eventId, CalendarEventDetails details) {
        restClient.patch()
                .uri(BASE_URL + "/{calendarId}/events/{eventId}", calendarId, eventId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .body(buildEventBody(details))
                .retrieve()
                .toBodilessEntity();
    }

    private ObjectNode buildEventBody(CalendarEventDetails details) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("summary", details.title());
        if (details.location() != null) {
            body.put("location", details.location());
        }
        if (details.description() != null) {
            body.put("description", details.description());
        }
        ObjectNode start = objectMapper.createObjectNode();
        ObjectNode end = objectMapper.createObjectNode();
        if (details.allDay()) {
            start.put("date", details.date().toString());
            end.put("date", details.date().plusDays(1).toString());
        } else {
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            start.put("dateTime", details.start().format(fmt));
            start.put("timeZone", TIME_ZONE);
            end.put("dateTime", details.end().format(fmt));
            end.put("timeZone", TIME_ZONE);
        }
        body.set("start", start);
        body.set("end", end);
        return body;
    }
}
