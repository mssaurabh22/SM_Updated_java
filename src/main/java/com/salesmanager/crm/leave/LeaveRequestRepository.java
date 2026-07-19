package com.salesmanager.crm.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here. JpaSpecificationExecutor backs the ADMIN-only "All
 * Requests" view's dynamic filter combinations (see LeaveRequestSpecifications), same pattern
 * as LeadRepository/ActivityLogRepository.
 */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID>,
        JpaSpecificationExecutor<LeaveRequest> {

    /** Backs "My Requests" (LeaveRequestService#listMine). */
    Page<LeaveRequest> findByEmployeeId(UUID employeeId, Pageable pageable);

    Page<LeaveRequest> findByEmployeeIdAndStatus(UUID employeeId, LeaveRequestStatus status, Pageable pageable);

    /** Backs the resolved-approver half of "Pending My Approval" (LeaveRequestService#listPendingMyApproval). */
    List<LeaveRequest> findByApproverIdAndStatus(UUID approverId, LeaveRequestStatus status);

    /**
     * Backs the ADMIN-only "unassigned pending" fallback pool - requests from an employee with
     * no managerId set, still awaiting a decision. Merged with the resolved-approver query above
     * in LeaveRequestService#listPendingMyApproval, ADMIN callers only.
     */
    @Query("SELECT r FROM LeaveRequest r WHERE r.approverId IS NULL AND r.status = "
            + "com.salesmanager.crm.leave.LeaveRequestStatus.PENDING")
    List<LeaveRequest> findUnassignedPending();

    /**
     * Backs LeaveRequestService#create's overlap-conflict check: true if this employee already
     * holds a PENDING or APPROVED request whose [startDate, endDate] range overlaps the given
     * range (standard interval-overlap test: existing.start <= newEnd AND existing.end >= newStart).
     */
    @Query("SELECT COUNT(r) > 0 FROM LeaveRequest r WHERE r.employeeId = :employeeId "
            + "AND r.status IN (com.salesmanager.crm.leave.LeaveRequestStatus.PENDING, "
            + "com.salesmanager.crm.leave.LeaveRequestStatus.APPROVED) "
            + "AND r.startDate <= :endDate AND r.endDate >= :startDate")
    boolean existsOverlapping(@Param("employeeId") UUID employeeId,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate);

    /**
     * Backs attendance.AttendanceService's ON_LEAVE derivation - every APPROVED request for this
     * employee whose [startDate, endDate] range overlaps [startDate, endDate] (the attendance
     * range being derived), same interval-overlap test as #existsOverlapping above but PENDING
     * requests don't count (only an APPROVED leave actually means "on leave" for attendance
     * purposes) and this returns the matching rows themselves, not just an existence check.
     */
    @Query("SELECT r FROM LeaveRequest r WHERE r.employeeId = :employeeId "
            + "AND r.status = com.salesmanager.crm.leave.LeaveRequestStatus.APPROVED "
            + "AND r.startDate <= :endDate AND r.endDate >= :startDate")
    List<LeaveRequest> findApprovedOverlapping(@Param("employeeId") UUID employeeId,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    /**
     * Backs EmployeeLeaveBalanceService#getBalanceSummary's "used days" computation - always
     * derived at read time from APPROVED requests, never stored/denormalized (see
     * EmployeeLeaveBalance's class javadoc). COALESCE guards against a null SUM when there are
     * no matching rows at all.
     */
    @Query("SELECT COALESCE(SUM(r.totalDays), 0) FROM LeaveRequest r WHERE r.employeeId = :employeeId "
            + "AND r.leaveTypeId = :leaveTypeId AND r.status = com.salesmanager.crm.leave.LeaveRequestStatus.APPROVED "
            + "AND EXTRACT(YEAR FROM r.startDate) = :year")
    BigDecimal sumApprovedDays(@Param("employeeId") UUID employeeId,
                               @Param("leaveTypeId") UUID leaveTypeId,
                               @Param("year") int year);

    /**
     * Backs the Team Leave Calendar (leave.LeaveRequestService#listTeamCalendar) and the HR
     * dashboard's scoped on-leave-today/leave-utilization aggregates (hrdashboard.
     * HrDashboardService) for a non-ADMIN caller: every APPROVED request among the given
     * employeeIds whose [startDate, endDate] range overlaps [startDate, endDate] - same
     * overlap test as #findApprovedOverlapping, just across a scope of employees instead of
     * one. Callers must guard against an empty {@code employeeIds} themselves (an empty "IN"
     * list is a meaningless query - same "empty scope, no query, empty/zero result" discipline
     * as elsewhere) rather than relying on this method to special-case it.
     */
    @Query("SELECT r FROM LeaveRequest r WHERE r.employeeId IN :employeeIds "
            + "AND r.status = com.salesmanager.crm.leave.LeaveRequestStatus.APPROVED "
            + "AND r.startDate <= :endDate AND r.endDate >= :startDate")
    List<LeaveRequest> findApprovedOverlappingForEmployees(@Param("employeeIds") Collection<UUID> employeeIds,
                                                            @Param("startDate") LocalDate startDate,
                                                            @Param("endDate") LocalDate endDate);

    /**
     * Backs the Team Leave Calendar for an ADMIN caller (plan B.4: "for Admin, the whole org's")
     * - every APPROVED request overlapping [startDate, endDate], org-wide, with no employee-id
     * filtering at all (scoped only by the Hibernate tenantFilter/RLS, same as every other read).
     */
    @Query("SELECT r FROM LeaveRequest r WHERE "
            + "r.status = com.salesmanager.crm.leave.LeaveRequestStatus.APPROVED "
            + "AND r.startDate <= :endDate AND r.endDate >= :startDate")
    List<LeaveRequest> findApprovedOverlappingOrgWide(@Param("startDate") LocalDate startDate,
                                                       @Param("endDate") LocalDate endDate);

    /**
     * Backs the HR dashboard's leave-utilization endpoint (hrdashboard.HrDashboardService): one
     * grouped-SUM query for the whole scope rather than one #sumApprovedDays call per
     * (employee, leaveType) pair - a leaveTypeId with zero APPROVED usage across the whole scope
     * simply produces no row here; HrDashboardService fills those in as an explicit 0 average.
     * Callers must guard against an empty {@code employeeIds} themselves, same as
     * #findApprovedOverlappingForEmployees above.
     */
    @Query("SELECT r.leaveTypeId AS leaveTypeId, COALESCE(SUM(r.totalDays), 0) AS totalDays FROM LeaveRequest r "
            + "WHERE r.employeeId IN :employeeIds AND r.status = com.salesmanager.crm.leave.LeaveRequestStatus.APPROVED "
            + "AND EXTRACT(YEAR FROM r.startDate) = :year GROUP BY r.leaveTypeId")
    List<LeaveTypeUsageSum> sumApprovedDaysByLeaveTypeForEmployees(@Param("employeeIds") Collection<UUID> employeeIds,
                                                                    @Param("year") int year);
}
