package com.salesmanager.crm.calendar;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Maps a short-lived, single-use OAuth {@code state} token to the employee/org that initiated
 * the connect flow (minted during the normally-authenticated "get authorize URL" call), so that
 * Google/Microsoft's redirect back to our unauthenticated {@code /calendar-connections/{provider}
 * /callback} endpoint (see SecurityConfig) can still recover who this is for. Deliberately a
 * plain in-memory map, not a DB table or Redis - this app runs as a single instance (see
 * CRM_IMPLEMENTATION.md Section 18), and the whole flow completes within a couple of minutes of
 * the user clicking "Connect", so surviving an app restart mid-flow isn't worth the added
 * complexity; a restart mid-flow just means the user clicks Connect again.
 */
@Component
public class CalendarOAuthStateStore {

    private static final long TTL_MILLIS = 10 * 60 * 1000;

    private record PendingState(UUID employeeId, UUID organizationId, CalendarProvider provider, Instant createdAt) {
    }

    private final Map<String, PendingState> states = new ConcurrentHashMap<>();

    public String create(UUID employeeId, UUID organizationId, CalendarProvider provider) {
        String state = UUID.randomUUID().toString();
        states.put(state, new PendingState(employeeId, organizationId, provider, Instant.now()));
        return state;
    }

    /** Single-use: removes the entry regardless of outcome, so a state token can never be
     * replayed. Returns null if unknown, already-consumed, expired, or for the wrong provider
     * (a defensive mismatch check - Google/Microsoft always echo back exactly the state we
     * gave them, so a mismatch here would indicate tampering, not a normal condition). */
    public Resolved consume(String state, CalendarProvider expectedProvider) {
        PendingState pending = states.remove(state);
        if (pending == null) {
            return null;
        }
        if (pending.provider() != expectedProvider) {
            return null;
        }
        if (Instant.now().isAfter(pending.createdAt().plusMillis(TTL_MILLIS))) {
            return null;
        }
        return new Resolved(pending.employeeId(), pending.organizationId());
    }

    public record Resolved(UUID employeeId, UUID organizationId) {
    }
}
