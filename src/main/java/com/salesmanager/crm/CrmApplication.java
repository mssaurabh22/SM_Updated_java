package com.salesmanager.crm;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} activates Spring's {@code @Scheduled} method processing for the
 * whole application context - required for Phase 4's scheduler.MissedVisitJob/LapsedLeadJob
 * to actually run; without it, {@code @Scheduled} annotations are silently ignored.
 */
@SpringBootApplication
@EnableScheduling
public class CrmApplication {

    public static void main(String[] args) {
        // Run in UTC regardless of host locale/OS timezone. Without this, the JVM's default
        // timezone (derived from the host OS) is sent to Postgres as the JDBC session TimeZone
        // parameter on every connection; on some Windows locales this resolves to a legacy
        // alias (e.g. "Asia/Calcutta" instead of "Asia/Kolkata") that Postgres's tzdata rejects
        // outright, failing every DB connection. A multi-tenant SaaS backend should store and
        // reason about all timestamps in UTC anyway, independent of any developer's or server's
        // local timezone - this makes that explicit rather than accidental.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CrmApplication.class, args);
    }
}
