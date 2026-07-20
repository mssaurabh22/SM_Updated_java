package com.salesmanager.crm.notification;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here. recipientId scoping (never letting anyone read anyone
 * else's notifications) is enforced in NotificationService, not here.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);

    Page<Notification> findByRecipientIdAndRead(UUID recipientId, boolean read, Pageable pageable);

    /** Backs the bell badge/unread-count endpoint - a lightweight COUNT, not a full page fetch. */
    long countByRecipientIdAndRead(UUID recipientId, boolean read);

    /**
     * Bulk "mark all as read" (NotificationService#markAllRead). A bulk JPQL UPDATE bypasses
     * the Hibernate {@code tenantFilter} entirely (a known Hibernate limitation - filters only
     * apply to SELECT/entity-loading, never to bulk UPDATE/DELETE HQL), but that's safe here:
     * {@code recipientId} is itself an employee's real database id, globally unique across
     * every org, so scoping by it alone already can never touch another organization's rows -
     * same reasoning as NotificationService#markRead's single-row lookup, which also never
     * adds an explicit organizationId condition.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipientId = :recipientId AND n.read = false")
    int markAllReadForRecipient(@Param("recipientId") UUID recipientId);
}
