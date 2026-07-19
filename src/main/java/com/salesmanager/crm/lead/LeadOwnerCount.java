package com.salesmanager.crm.lead;

import java.util.UUID;

/**
 * Spring Data interface projection backing LeadRepository#countGroupedByOwner - one row per
 * distinct ownerId with at least one lead in the org. ReportingService resolves ownerId to
 * the owning Employee's fullName for the byOwner breakdown in GET /reports/pipeline-summary.
 */
public interface LeadOwnerCount {

    UUID getOwnerId();

    Long getLeadCount();

    Long getClosedWonCount();
}
