package com.salesmanager.crm.visit;

/**
 * Spring Data interface projection backing VisitRepository#countGroupedByStatus - one row per
 * distinct VisitStatus present among the (optionally date-filtered) visits in the org.
 */
public interface VisitStatusCount {

    VisitStatus getStatus();

    Long getCount();
}
