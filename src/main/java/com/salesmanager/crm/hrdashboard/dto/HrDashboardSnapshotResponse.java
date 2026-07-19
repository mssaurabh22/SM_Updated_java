package com.salesmanager.crm.hrdashboard.dto;

/**
 * Plan B.5's "at-a-glance" HR dashboard stats. {@code onLeaveToday}/{@code notClockedInToday}
 * are scoped to the caller's visible team (ADMIN -> whole org, else -> subordinate chain, via
 * employee.EmployeeHierarchyService); {@code pendingApprovalsForMe} is deliberately NOT scoped
 * that way - it's the caller's own resolved-approver inbox (plus the Admin-fallback pool if
 * they're an Admin), same value leave.LeaveRequestService#listPendingMyApproval already returns
 * for GET /leave-requests/pending-approval.
 */
public record HrDashboardSnapshotResponse(int onLeaveToday, int notClockedInToday, int pendingApprovalsForMe) {
}
