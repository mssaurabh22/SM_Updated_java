package com.salesmanager.crm.calendar;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A short, explicit timeout on every outbound call to Google/Microsoft's APIs - without this,
 * an unreachable network (or a slow/hanging provider) would block on the JDK's default,
 * effectively-unbounded connection timeout. A calendar sync failure must fail FAST and be
 * caught by CalendarSyncService's try/catch, not hang the AFTER_COMMIT listener thread.
 */
final class CalendarHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private CalendarHttpClient() {
    }

    static RestClient create() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }
}
