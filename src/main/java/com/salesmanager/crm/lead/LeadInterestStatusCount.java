package com.salesmanager.crm.lead;

import java.util.UUID;

/**
 * Spring Data interface projection backing LeadRepository#countGroupedByInterestLevelAndStatus
 * - one row per distinct (interestLevelId, status) combination present in the org.
 * interestLevelId is nullable (a lead with no interest level set, or a free-text
 * interestLevelOther override) - ReportingService resolves ids to labels and buckets null (or
 * an id that no longer resolves) into "Not Set", same convention as LeadSourceCount/
 * leadsBySource().
 */
public interface LeadInterestStatusCount {

    UUID getInterestLevelId();

    LeadStatus getStatus();

    Long getCount();
}
