package com.salesmanager.crm.leave;

/**
 * Lifecycle states for a LeaveRequest (plan Part B.3). PENDING is the only state a request can
 * be approved/rejected/cancelled from; APPROVED/REJECTED are set by LeaveRequestService#approve/
 * #reject (the resolved approver or any ADMIN); CANCELLED is set only by the requesting
 * employee themself, and only while still PENDING.
 */
public enum LeaveRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}
