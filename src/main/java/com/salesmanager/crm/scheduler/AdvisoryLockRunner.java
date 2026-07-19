package com.salesmanager.crm.scheduler;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Guards a scheduled job body with a Postgres session-scoped advisory lock
 * ({@code pg_try_advisory_lock}/{@code pg_advisory_unlock}), so that if this application is
 * ever scaled to more than one instance, only one instance's run of a given job actually does
 * work at a time - every other instance's {@code pg_try_advisory_lock} call returns false and
 * that instance's run is a no-op. Not needed for correctness on a single instance today (Spring
 * never invokes one {@code @Scheduled} method concurrently with itself), but cheap and correct
 * to have in place before it's ever needed.
 *
 * Advisory locks are tied to the physical DB session/connection that acquired them, NOT to the
 * transaction - so the lock-acquire, the guarded work, and the unlock MUST all run on the same
 * connection. Callers achieve this simply by invoking {@link #runExclusive} from within a
 * single {@code @Transactional} method using the same injected {@link EntityManager} for
 * everything: Spring binds one physical connection to that method's transaction for its whole
 * duration, so the lock/work/unlock native queries below all reuse it, never a different
 * pooled connection mid-way through.
 */
@Component
public class AdvisoryLockRunner {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLockRunner.class);

    /**
     * Attempts to acquire {@code lockKey}; if acquired, runs {@code work} then always releases
     * the lock (even if {@code work} throws). If another session already holds the lock, logs
     * at INFO and returns immediately without running {@code work} or touching the lock.
     */
    public void runExclusive(EntityManager entityManager, long lockKey, String jobName, Runnable work) {
        Object acquired = entityManager.createNativeQuery("SELECT pg_try_advisory_lock(:key)")
                .setParameter("key", lockKey)
                .getSingleResult();
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("{}: advisory lock {} already held by another instance/run - skipping this invocation",
                    jobName, lockKey);
            return;
        }
        try {
            work.run();
        } finally {
            entityManager.createNativeQuery("SELECT pg_advisory_unlock(:key)")
                    .setParameter("key", lockKey)
                    .getSingleResult();
        }
    }
}
