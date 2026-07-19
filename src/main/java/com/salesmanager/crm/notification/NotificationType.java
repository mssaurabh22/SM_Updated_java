package com.salesmanager.crm.notification;

/**
 * Kept deliberately minimal for Phase 3 - just the one event that currently needs to notify
 * an employee. Add more values here (and the corresponding NotificationService#create call
 * site) as future phases introduce more notification-worthy events.
 *
 * Phase 4 adds VISIT_MISSED (scheduler.MissedVisitJob - sent to every ADMIN in the visit's
 * org AND to the visit's own owner, derived from the parent Lead's ownerId, since a missed
 * visit is both a management escalation and something the rep who owns it needs to hear
 * directly) and LEAD_LAPSED (scheduler.LapsedLeadJob - sent only to the lead's owner, a
 * personal follow-up reminder).
 *
 * LEAD_LAPSED_DIGEST is a separate, later addition: sent to every ADMIN in an org, once per
 * nightly LapsedLeadJob run, summarizing that run's whole batch of leads that just lapsed in
 * that org (a single lightweight "N of your team's leads lapsed last night" nudge) - distinct
 * from LEAD_LAPSED, which keeps going to each lapsed lead's individual owner unchanged and is
 * never sent to Admins.
 *
 * Part B of the Employee Entitlement plan (leave.LeaveRequestService) adds three more:
 * LEAVE_REQUEST_SUBMITTED (sent to the submitting employee's resolved approver - their
 * managerId - or, if they have no manager, to every ADMIN in the org as the routing fallback),
 * LEAVE_REQUEST_APPROVED and LEAVE_REQUEST_REJECTED (both sent only to the requesting employee,
 * once their manager or an ADMIN decides the request).
 */
public enum NotificationType {
    LEAD_REASSIGNED,
    VISIT_MISSED,
    LEAD_LAPSED,
    LEAD_LAPSED_DIGEST,
    LEAVE_REQUEST_SUBMITTED,
    LEAVE_REQUEST_APPROVED,
    LEAVE_REQUEST_REJECTED
}
