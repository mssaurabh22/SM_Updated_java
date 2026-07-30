package com.salesmanager.crm.lead;

import java.util.UUID;

/**
 * Spring Data interface projection backing LeadRepository#countGroupedByLeadSource - one row
 * per distinct leadSourceId present in the org's leads, PLUS one row with a null leadSourceId
 * aggregating every lead that has none set (free-text leadSourceOther or genuinely blank).
 * ReportingService resolves leadSourceId to its master-data label (or "Other" for the null
 * row) for the Dashboard's Leads by Source chart.
 */
public interface LeadSourceCount {

    UUID getLeadSourceId();

    Long getCount();
}
