package com.salesmanager.crm.lead;

/**
 * Spring Data interface projection backing LeadRepository#countGroupedByStatus - one row per
 * distinct LeadStatus present in the org's leads (statuses with zero leads simply don't
 * appear; ReportingService fills those in as zero-count buckets).
 */
public interface LeadStatusCount {

    LeadStatus getStatus();

    Long getCount();
}
