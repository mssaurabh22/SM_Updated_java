package com.salesmanager.crm.calendar.dto;

import com.salesmanager.crm.calendar.CalendarConnection;
import com.salesmanager.crm.calendar.CalendarProvider;
import java.time.OffsetDateTime;

/** "Not connected" is a normal status, not an error - GET /calendar-connections/me always
 * returns 200 with connected=false rather than a 404, unlike most other single-resource lookups
 * in this codebase. Never exposes accessTokenEncrypted/refreshTokenEncrypted - those never
 * leave the backend. */
public record CalendarConnectionResponse(
        boolean connected,
        CalendarProvider provider,
        OffsetDateTime connectedAt,
        String lastSyncError) {

    public static CalendarConnectionResponse notConnected() {
        return new CalendarConnectionResponse(false, null, null, null);
    }

    public static CalendarConnectionResponse from(CalendarConnection connection) {
        return new CalendarConnectionResponse(
                true, connection.getProvider(), connection.getConnectedAt(), connection.getLastSyncError());
    }
}
