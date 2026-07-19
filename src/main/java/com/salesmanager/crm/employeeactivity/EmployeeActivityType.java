package com.salesmanager.crm.employeeactivity;

/**
 * The set of Leave lifecycle moments the employee-activity history records. Each value
 * corresponds to exactly one EmployeeActivityLogService#record call site - see
 * leave.LeaveRequestService (SUBMITTED on create, APPROVED/REJECTED on decide, CANCELLED on
 * cancel). Deliberately a separate enum/table from activity.ActivityType/ActivityLog - see
 * EmployeeActivityLog's class javadoc for why the two histories are not merged.
 */
public enum EmployeeActivityType {
    LEAVE_REQUEST_SUBMITTED,
    LEAVE_REQUEST_APPROVED,
    LEAVE_REQUEST_REJECTED,
    LEAVE_REQUEST_CANCELLED
}
