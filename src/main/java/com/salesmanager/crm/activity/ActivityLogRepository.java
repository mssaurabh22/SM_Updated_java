package com.salesmanager.crm.activity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here. JpaSpecificationExecutor backs ActivityLogService#list's
 * dynamic leadId/ownerId/type filter combinations (see ActivityLogSpecifications), same
 * pattern as LeadRepository/VisitRepository.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID>, JpaSpecificationExecutor<ActivityLog> {
}
