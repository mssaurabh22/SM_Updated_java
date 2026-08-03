package com.salesmanager.crm.activity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here. JpaSpecificationExecutor backs ActivityLogService#list's
 * dynamic leadId/ownerId/type filter combinations (see ActivityLogSpecifications), same
 * pattern as LeadRepository/VisitRepository.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID>, JpaSpecificationExecutor<ActivityLog> {

    /**
     * Backs ReportingService#teamProgress's "last activity" column - one row per ownerId with
     * at least one activity entry within the given scope, most-recent createdAt only.
     */
    @Query("SELECT a.ownerId AS ownerId, MAX(a.createdAt) AS lastActivityAt FROM ActivityLog a "
            + "WHERE a.ownerId IN :ownerIds GROUP BY a.ownerId")
    List<OwnerLastActivity> findLastActivityForOwners(@Param("ownerIds") Set<UUID> ownerIds);
}
