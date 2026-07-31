package com.salesmanager.crm.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Plain REST calls against Microsoft Graph's /me/events - see CalendarOAuthService's class
 * javadoc for why no provider SDK is used here. Always targets the signed-in user's default
 * calendar (Graph has no "primary" alias the way Google does - CalendarConnection#calendarId
 * is meaningful for Google only, not read here). */
@Component
public class OutlookCalendarClient {

    private static final String BASE_URL = "https://graph.microsoft.com/v1.0/me/events";
    private static final String TIME_ZONE = "Asia/Kolkata";

    private final RestClient restClient = CalendarHttpClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String createEvent(String accessToken, CalendarEventDetails details) {
        JsonNode response = restClient.post()
                .uri(BASE_URL)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .body(buildEventBody(details))
                .retrieve()
                .body(JsonNode.class);
        return response.get("id").asText();
    }

    public void updateEvent(String accessToken, String eventId, CalendarEventDetails details) {
        restClient.patch()
                .uri(BASE_URL + "/{eventId}", eventId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .body(buildEventBody(details))
                .retrieve()
                .toBodilessEntity();
    }

    private ObjectNode buildEventBody(CalendarEventDetails details) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("subject", details.title());
        if (details.location() != null) {
            ObjectNode location = objectMapper.createObjectNode();
            location.put("displayName", details.location());
            body.set("location", location);
        }
        if (details.description() != null) {
            ObjectNode bodyContent = objectMapper.createObjectNode();
            bodyContent.put("contentType", "text");
            bodyContent.put("content", details.description());
            body.set("body", bodyContent);
        }
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        ObjectNode start = objectMapper.createObjectNode();
        ObjectNode end = objectMapper.createObjectNode();
        if (details.allDay()) {
            body.put("isAllDay", true);
            start.put("dateTime", details.date().atTime(LocalTime.MIDNIGHT).format(fmt));
            start.put("timeZone", TIME_ZONE);
            end.put("dateTime", details.date().plusDays(1).atTime(LocalTime.MIDNIGHT).format(fmt));
            end.put("timeZone", TIME_ZONE);
        } else {
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
