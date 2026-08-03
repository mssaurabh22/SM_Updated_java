package com.salesmanager.crm.lead;

import java.util.UUID;

/**
 * Spring Data interface projection backing LeadRepository#countGroupedByOwnerAndStatusForOwners -
 * one row per distinct (ownerId, status) combination actually present, for the Team Progress
 * view's per-member lead breakdown. Combinations with zero leads simply don't appear;
 * ReportingService fills those in as zero-count buckets, same convention as LeadStatusCount.
 */
public interface LeadOwnerStatusCount {

    UUID getOwnerId();

    LeadStatus getStatus();

    Long getCount();
}
