package com.salesmanager.crm.visit;

/**
 * Spring Data interface projection backing VisitRepository#countGroupedByVisitType - one row
 * per distinct VisitType present among the (optionally date-filtered) visits in the org, same
 * shape as VisitStatusCount.
 */
public interface VisitTypeCount {

    VisitType getVisitType();

    Long getCount();
}
