package com.salesmanager.crm.leave;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic WHERE clauses for GET /leave-requests' optional employeeId/status/leaveTypeId/
 * date-range filters, composed via Specification.where(...).and(...) in
 * LeaveRequestService#listAll - same pattern as activity.ActivityLogSpecifications/
 * lead.LeadSpecifications. Each method returns null for a null filter value, which
 * Specification.and(...) treats as "no additional restriction".
 */
final class LeaveRequestSpecifications {

    private LeaveRequestSpecifications() {
    }

    static Specification<LeaveRequest> hasEmployeeId(UUID employeeId) {
        return (root, query, cb) -> employeeId == null ? null : cb.equal(root.get("employeeId"), employeeId);
    }

    static Specification<LeaveRequest> hasStatus(LeaveRequestStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<LeaveRequest> hasLeaveTypeId(UUID leaveTypeId) {
        return (root, query, cb) -> leaveTypeId == null ? null : cb.equal(root.get("leaveTypeId"), leaveTypeId);
    }

    static Specification<LeaveRequest> startDateFrom(LocalDate startDateFrom) {
        return (root, query, cb) ->
                startDateFrom == null ? null : cb.greaterThanOrEqualTo(root.get("startDate"), startDateFrom);
    }

    static Specification<LeaveRequest> startDateTo(LocalDate startDateTo) {
        return (root, query, cb) ->
                startDateTo == null ? null : cb.lessThanOrEqualTo(root.get("startDate"), startDateTo);
    }
}
