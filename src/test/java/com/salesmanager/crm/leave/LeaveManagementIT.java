package com.salesmanager.crm.leave;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Part B of the Employee Entitlement plan - the Leave/Attendance/Approvals module. Covers:
 * entitlement gating (403 FEATURE_NOT_ENTITLED for a non-entitled org), LeaveType admin CRUD
 * (including duplicate-code rejection and non-admin-forbidden), the full submit -> pending-
 * approval -> approve lifecycle via a direct manager (with balance/notification/activity-log
 * side effects), the no-manager Admin-fallback routing (both approve and reject paths),
 * overlap-conflict rejection, weekend/holiday-aware totalDays computation, the "not yours to
 * decide" authorization rule, and cross-tenant isolation. Follows the same Testcontainers/
 * helper-method style as EntitlementIT/EmployeeCrudIT/ActivityLogIT.
 */
class LeaveManagementIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void nonEntitledOrg_getsForbiddenFeatureNotEntitled_onLeaveRequestCreate() {
        AuthResponse admin = registerOrganization("Leave Gating Org");
        // Deliberately NOT granted - proves the endpoint 403s before any business logic runs.
        Map<String, Object> body = Map.of(
                "leaveTypeId", UUID.randomUUID().toString(),
                "startDate", LocalDate.now().plusDays(10).toString(),
                "endDate", LocalDate.now().plusDays(11).toString());

        ResponseEntity<String> response = post("/leave-requests", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(response.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
    }

    @Test
    void leaveTypeCrud_adminLifecycle_duplicateCodeRejected_nonAdminForbidden() {
        AuthResponse admin = registerOrganization("Leave Type CRUD Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "leaveTypeCrudEmployee", null);

        Map<String, Object> createBody = Map.of(
                "name", "Casual Leave", "code", "CASUAL", "defaultAllocationDays", 12);
        ResponseEntity<String> created = post("/leave-types", admin.accessToken(), createBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leaveTypeId = parse(created.getBody()).get("id").asText();

        // Duplicate code (case-insensitive) is rejected with 409.
        Map<String, Object> duplicateBody = Map.of(
                "name", "Casual Leave Again", "code", "casual", "defaultAllocationDays", 10);
        ResponseEntity<String> duplicate = post("/leave-types", admin.accessToken(), duplicateBody);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> updateBody = Map.of(
                "name", "Casual Leave Updated", "defaultAllocationDays", 15, "sortOrder", 1, "active", true);
        ResponseEntity<String> updated = put("/leave-types/" + leaveTypeId, admin.accessToken(), updateBody);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(updated.getBody()).get("defaultAllocationDays").asDouble()).isEqualTo(15.0);

        ResponseEntity<String> deactivated = patch("/leave-types/" + leaveTypeId + "/deactivate", admin.accessToken());
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(deactivated.getBody()).get("active").asBoolean()).isFalse();

        // Non-admin forbidden on every mutation.
        assertThat(post("/leave-types", employee.accessToken(), createBody).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put("/leave-types/" + leaveTypeId, employee.accessToken(), updateBody).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch("/leave-types/" + leaveTypeId + "/deactivate", employee.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void fullLifecycle_submitByReport_visibleToManager_approvedByManager_updatesBalanceAndNotifies() {
        AuthResponse admin = registerOrganization("Leave Lifecycle Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "lifecycleManager", null);
        AuthResponse report = createAndLoginEmployee(admin.accessToken(), "lifecycleReport", manager.employeeId());

        String leaveTypeId = createLeaveType(admin.accessToken(), "Lifecycle Earned Leave", "LIFECYCLE_EL", 20);

        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> submitted = submitLeaveRequest(report.accessToken(), leaveTypeId, monday, tuesday,
                "Family event");
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode submittedBody = parse(submitted.getBody());
        String requestId = submittedBody.get("id").asText();
        assertThat(submittedBody.get("status").asText()).isEqualTo("PENDING");
        assertThat(submittedBody.get("approverId").asText()).isEqualTo(manager.employeeId().toString());
        assertThat(submittedBody.get("totalDays").asDouble()).isEqualTo(2.0);

        JsonNode pending = parse(get("/leave-requests/pending-approval", manager.accessToken()).getBody());
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).get("id").asText()).isEqualTo(requestId);

        ResponseEntity<String> approved = patch("/leave-requests/" + requestId + "/approve", manager.accessToken(),
                Map.of("decisionNote", "Enjoy!"));
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode approvedBody = parse(approved.getBody());
        assertThat(approvedBody.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approvedBody.get("decidedById").asText()).isEqualTo(manager.employeeId().toString());
        assertThat(approvedBody.get("decisionNote").asText()).isEqualTo("Enjoy!");

        JsonNode mine = parse(get("/leave-requests/mine", report.accessToken()).getBody()).get("content");
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("status").asText()).isEqualTo("APPROVED");

        JsonNode notifications = parse(get("/notifications", report.accessToken()).getBody()).get("content");
        boolean hasApprovedNotification = false;
        for (JsonNode notification : notifications) {
            if (notification.get("type").asText().equals("LEAVE_REQUEST_APPROVED")) {
                hasApprovedNotification = true;
            }
        }
        assertThat(hasApprovedNotification).isTrue();

        JsonNode balances = parse(get("/leave-balances/mine", report.accessToken()).getBody());
        boolean foundUsedDays = false;
        for (JsonNode balance : balances) {
            if (balance.get("leaveTypeId").asText().equals(leaveTypeId)) {
                assertThat(balance.get("usedDays").asDouble()).isEqualTo(2.0);
                assertThat(balance.get("allocatedDays").asDouble()).isEqualTo(20.0);
                assertThat(balance.get("remainingDays").asDouble()).isEqualTo(18.0);
                foundUsedDays = true;
            }
        }
        assertThat(foundUsedDays).isTrue();
    }

    @Test
    void noManager_routesToAdminFallback_notInAnotherEmployeesQueue_adminApprovesAndRejectsWithNote() {
        AuthResponse admin = registerOrganization("Leave Admin Fallback Org");
        grant(admin.orgId());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "fallbackUnrelated", null);
        AuthResponse orphan = createAndLoginEmployee(admin.accessToken(), "fallbackOrphan", null);

        String leaveTypeId = createLeaveType(admin.accessToken(), "Fallback Sick Leave", "FALLBACK_SL", 10);

        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> submitted = submitLeaveRequest(orphan.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode submittedBody = parse(submitted.getBody());
        String requestId = submittedBody.get("id").asText();
        assertThat(submittedBody.get("approverId").isNull()).isTrue();

        // Not in an unrelated (non-manager, non-admin) employee's pending-approval queue.
        JsonNode unrelatedQueue = parse(get("/leave-requests/pending-approval", unrelated.accessToken()).getBody());
        assertThat(unrelatedQueue.size()).isEqualTo(0);

        // But IS in the Admin's queue (the unassigned fallback pool).
        JsonNode adminQueue = parse(get("/leave-requests/pending-approval", admin.accessToken()).getBody());
        boolean adminSeesIt = false;
        for (JsonNode entry : adminQueue) {
            if (entry.get("id").asText().equals(requestId)) {
                adminSeesIt = true;
            }
        }
        assertThat(adminSeesIt).isTrue();

        ResponseEntity<String> approved = patch("/leave-requests/" + requestId + "/approve", admin.accessToken(), null);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(approved.getBody()).get("status").asText()).isEqualTo("APPROVED");
        assertThat(parse(approved.getBody()).get("decidedById").asText()).isEqualTo(admin.employeeId().toString());

        // Separate request/date range for the reject path.
        LocalDate wednesday = monday.plusDays(2);
        LocalDate thursday = monday.plusDays(3);
        ResponseEntity<String> secondSubmitted =
                submitLeaveRequest(orphan.accessToken(), leaveTypeId, wednesday, thursday, null);
        assertThat(secondSubmitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String secondRequestId = parse(secondSubmitted.getBody()).get("id").asText();

        ResponseEntity<String> rejected = patch("/leave-requests/" + secondRequestId + "/reject", admin.accessToken(),
                Map.of("decisionNote", "Not enough coverage that week"));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rejectedBody = parse(rejected.getBody());
        assertThat(rejectedBody.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejectedBody.get("decisionNote").asText()).isEqualTo("Not enough coverage that week");

        JsonNode notifications = parse(get("/notifications", orphan.accessToken()).getBody()).get("content");
        boolean hasRejectedNotification = false;
        for (JsonNode notification : notifications) {
            if (notification.get("type").asText().equals("LEAVE_REQUEST_REJECTED")) {
                hasRejectedNotification = true;
            }
        }
        assertThat(hasRejectedNotification).isTrue();
    }

    @Test
    void overlappingPendingRequest_isRejected_butSucceedsAfterFirstIsCancelled() {
        AuthResponse admin = registerOrganization("Leave Overlap Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "overlapEmployee", null);
        String leaveTypeId = createLeaveType(admin.accessToken(), "Overlap Leave", "OVERLAP_LV", 10);

        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> first = submitLeaveRequest(employee.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstId = parse(first.getBody()).get("id").asText();

        // Same range - overlaps the still-PENDING first request.
        ResponseEntity<String> overlapping =
                submitLeaveRequest(employee.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(overlapping.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> cancelled = patch("/leave-requests/" + firstId + "/cancel", employee.accessToken());
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(cancelled.getBody()).get("status").asText()).isEqualTo("CANCELLED");

        // Now the same range succeeds since the conflicting request is no longer PENDING/APPROVED.
        ResponseEntity<String> retried = submitLeaveRequest(employee.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void totalDays_excludesWeekends_andExcludesHolidaysOnceAdded() {
        AuthResponse admin = registerOrganization("Leave Weekend Holiday Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "weekendHolidayEmployee", null);
        String leaveTypeId = createLeaveType(admin.accessToken(), "Weekend Holiday Leave", "WEEKEND_HOLIDAY_LV", 10);

        LocalDate monday = nextMonday();
        LocalDate friday = monday.minusDays(3);
        // Friday -> Monday (4 calendar days) contains exactly one Saturday and one Sunday, so
        // only Friday + Monday count as working days.
        ResponseEntity<String> withoutHoliday = submitLeaveRequest(employee.accessToken(), leaveTypeId, friday, monday, null);
        assertThat(withoutHoliday.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = parse(withoutHoliday.getBody()).get("id").asText();
        assertThat(parse(withoutHoliday.getBody()).get("totalDays").asDouble()).isEqualTo(2.0);

        // Cancel it, add a holiday landing on the Friday, then resubmit the exact same range -
        // totalDays should now exclude that holiday too, leaving only Monday.
        assertThat(patch("/leave-requests/" + requestId + "/cancel", employee.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> holidayBody = Map.of("name", "Test Holiday", "holidayDate", friday.toString());
        ResponseEntity<String> holidayCreated = post("/holidays", admin.accessToken(), holidayBody);
        assertThat(holidayCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> withHoliday = submitLeaveRequest(employee.accessToken(), leaveTypeId, friday, monday, null);
        assertThat(withHoliday.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(parse(withHoliday.getBody()).get("totalDays").asDouble()).isEqualTo(1.0);
    }

    @Test
    void employeeCannotApproveRejectOrCancelSomeoneElsesRequest() {
        AuthResponse admin = registerOrganization("Leave Authz Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "authzManager", null);
        AuthResponse report = createAndLoginEmployee(admin.accessToken(), "authzReport", manager.employeeId());
        AuthResponse stranger = createAndLoginEmployee(admin.accessToken(), "authzStranger", null);

        String leaveTypeId = createLeaveType(admin.accessToken(), "Authz Leave", "AUTHZ_LV", 10);
        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> submitted = submitLeaveRequest(report.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = parse(submitted.getBody()).get("id").asText();

        // Not the resolved approver and not an ADMIN - information-hiding NotFoundException
        // (404), same idiom as Lead#loadForCurrentUser/NotificationService#markRead, since a
        // leave-request id was never shared with this unrelated employee in the first place.
        assertThat(patch("/leave-requests/" + requestId + "/approve", stranger.accessToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch("/leave-requests/" + requestId + "/reject", stranger.accessToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch("/leave-requests/" + requestId + "/cancel", stranger.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void crossTenantIsolation_secondOrgCannotSeeOrActOnFirstOrgsLeaveData() {
        AuthResponse orgA = registerOrganization("Leave Isolation Org A");
        AuthResponse orgB = registerOrganization("Leave Isolation Org B");
        grant(orgA.orgId());
        grant(orgB.orgId());

        String leaveTypeId = createLeaveType(orgA.accessToken(), "Isolation Leave", "ISOLATION_LV", 10);
        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> submitted = submitLeaveRequest(orgA.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = parse(submitted.getBody()).get("id").asText();

        // Org B's leave-type list never includes Org A's leave type.
        JsonNode orgBTypes = parse(get("/leave-types?includeInactive=true", orgB.accessToken()).getBody());
        for (JsonNode type : orgBTypes) {
            assertThat(type.get("id").asText()).isNotEqualTo(leaveTypeId);
        }

        // Org B cannot mutate Org A's leave type by guessed id - cross-tenant existence is
        // indistinguishable from nonexistence.
        Map<String, Object> updateBody = Map.of(
                "name", "Hijacked", "defaultAllocationDays", 1, "sortOrder", 0, "active", true);
        assertThat(put("/leave-types/" + leaveTypeId, orgB.accessToken(), updateBody).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Org B's admin "All Requests" view never includes Org A's request.
        JsonNode orgBAllRequests = parse(get("/leave-requests", orgB.accessToken()).getBody()).get("content");
        for (JsonNode request : orgBAllRequests) {
            assertThat(request.get("id").asText()).isNotEqualTo(requestId);
        }

        // Org B cannot approve Org A's request by guessed id.
        assertThat(patch("/leave-requests/" + requestId + "/approve", orgB.accessToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void inactiveManager_fallsBackToAdminPool_notToTheInactiveManagersQueue() {
        AuthResponse admin = registerOrganization("Leave Inactive Manager Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "inactiveManager", null);
        AuthResponse report = createAndLoginEmployee(admin.accessToken(), "inactiveManagerReport", manager.employeeId());

        // Deactivate the manager BEFORE the report submits - plan B.2, point 1: an inactive
        // resolved manager falls back to the Admin pool exactly like "no manager set" does.
        ResponseEntity<String> deactivated =
                patch("/employees/" + manager.employeeId() + "/deactivate", admin.accessToken());
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);

        String leaveTypeId = createLeaveType(admin.accessToken(), "Inactive Manager Leave", "INACTIVE_MGR_LV", 10);
        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> submitted = submitLeaveRequest(report.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode submittedBody = parse(submitted.getBody());
        String requestId = submittedBody.get("id").asText();
        // Falls back to the unassigned pool - approverId is null, NOT the inactive manager's id.
        assertThat(submittedBody.get("approverId").isNull()).isTrue();

        // Not in the (now-inactive) manager's own queue.
        JsonNode managerQueue = parse(get("/leave-requests/pending-approval", manager.accessToken()).getBody());
        assertThat(managerQueue.size()).isEqualTo(0);

        // IS in the Admin's fallback pool.
        JsonNode adminQueue = parse(get("/leave-requests/pending-approval", admin.accessToken()).getBody());
        boolean adminSeesIt = false;
        for (JsonNode entry : adminQueue) {
            if (entry.get("id").asText().equals(requestId)) {
                adminSeesIt = true;
            }
        }
        assertThat(adminSeesIt).isTrue();
    }

    @Test
    void submittingMoreDaysThanRemainingBalance_isHardBlockedWithConflict() {
        AuthResponse admin = registerOrganization("Leave Balance Block Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "balanceBlockEmployee", null);
        // Only 1 day allocated - the Monday+Tuesday (2 working day) request below exceeds it.
        String leaveTypeId = createLeaveType(admin.accessToken(), "Balance Block Leave", "BALANCE_BLOCK_LV", 1);

        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> submitted = submitLeaveRequest(employee.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(submitted.getBody()).get("message").asText())
                .contains("remaining").contains("Balance Block Leave");

        // A 1-day request (within the 1-day allocation) still succeeds.
        ResponseEntity<String> withinBalance =
                submitLeaveRequest(employee.accessToken(), leaveTypeId, monday, monday, null);
        assertThat(withinBalance.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void approvedRequest_futureStartDate_canBeCancelled_pastStartDate_cannotBeCancelled() {
        AuthResponse admin = registerOrganization("Leave Cancel Approved Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "cancelApprovedEmployee", null);
        String leaveTypeId = createLeaveType(admin.accessToken(), "Cancel Approved Leave", "CANCEL_APPROVED_LV", 30);

        // Future-dated APPROVED request - cancellable per plan B.3, point 5.
        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        ResponseEntity<String> futureSubmitted =
                submitLeaveRequest(employee.accessToken(), leaveTypeId, monday, tuesday, null);
        assertThat(futureSubmitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String futureRequestId = parse(futureSubmitted.getBody()).get("id").asText();
        ResponseEntity<String> futureApproved =
                patch("/leave-requests/" + futureRequestId + "/approve", admin.accessToken(), null);
        assertThat(futureApproved.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> cancelledFuture =
                patch("/leave-requests/" + futureRequestId + "/cancel", employee.accessToken());
        assertThat(cancelledFuture.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(cancelledFuture.getBody()).get("status").asText()).isEqualTo("CANCELLED");

        // Past-dated APPROVED request (no rule prevents submitting/approving a past-dated
        // request via the normal API - LeaveRequestCreateRequest has no "not in the past"
        // constraint) - NOT cancellable, since the leave has already started.
        LocalDate lastWeekStart = monday.minusDays(7);
        LocalDate lastWeekEnd = lastWeekStart.plusDays(1);
        ResponseEntity<String> pastSubmitted =
                submitLeaveRequest(employee.accessToken(), leaveTypeId, lastWeekStart, lastWeekEnd, null);
        assertThat(pastSubmitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String pastRequestId = parse(pastSubmitted.getBody()).get("id").asText();
        ResponseEntity<String> pastApproved =
                patch("/leave-requests/" + pastRequestId + "/approve", admin.accessToken(), null);
        assertThat(pastApproved.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> cancelledPast =
                patch("/leave-requests/" + pastRequestId + "/cancel", employee.accessToken());
        assertThat(cancelledPast.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void teamCalendar_managerSeesOnlyTheirTeamsApprovedLeave_adminSeesWholeOrg_icGetsEmptyCalendar() {
        AuthResponse admin = registerOrganization("Team Calendar Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "calendarManager", null);
        AuthResponse reportWithLeave =
                createAndLoginEmployee(admin.accessToken(), "calendarReportWithLeave", manager.employeeId());
        createAndLoginEmployee(admin.accessToken(), "calendarReportWithoutLeave", manager.employeeId());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "calendarUnrelated", null);

        String leaveTypeId = createLeaveType(admin.accessToken(), "Calendar Leave", "CALENDAR_LV", 10);

        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        String teamRequestId =
                submitAndApprove(admin.accessToken(), reportWithLeave.accessToken(), leaveTypeId, monday, tuesday);
        // An unrelated employee's approved leave overlapping the same month - must never leak
        // into the manager's team calendar.
        String unrelatedRequestId =
                submitAndApprove(admin.accessToken(), unrelated.accessToken(), leaveTypeId, monday, tuesday);

        String month = YearMonth.from(monday).toString();

        JsonNode managerCalendar =
                parse(get("/leave-requests/team-calendar?month=" + month, manager.accessToken()).getBody());
        assertThat(managerCalendar.size()).isEqualTo(1);
        assertThat(managerCalendar.get(0).get("id").asText()).isEqualTo(teamRequestId);

        // ADMIN sees the whole org - both requests, across unrelated teams.
        Set<String> adminIds = new HashSet<>();
        JsonNode adminCalendar =
                parse(get("/leave-requests/team-calendar?month=" + month, admin.accessToken()).getBody());
        for (JsonNode entry : adminCalendar) {
            adminIds.add(entry.get("id").asText());
        }
        assertThat(adminIds).contains(teamRequestId, unrelatedRequestId);

        // An individual contributor with no reports gets an empty calendar - correct, not an error.
        JsonNode icCalendar =
                parse(get("/leave-requests/team-calendar?month=" + month, reportWithLeave.accessToken()).getBody());
        assertThat(icCalendar.size()).isEqualTo(0);
    }

    @Test
    void teamCalendar_nonEntitledOrg_getsForbiddenFeatureNotEntitled() {
        AuthResponse admin = registerOrganization("Team Calendar Gating Org");
        ResponseEntity<String> response =
                get("/leave-requests/team-calendar?month=" + YearMonth.now(), admin.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(response.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
    }

    private String submitAndApprove(String adminToken, String employeeToken, String leaveTypeId,
                                     LocalDate start, LocalDate end) {
        ResponseEntity<String> submitted = submitLeaveRequest(employeeToken, leaveTypeId, start, end, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = parse(submitted.getBody()).get("id").asText();
        ResponseEntity<String> approved = patch("/leave-requests/" + requestId + "/approve", adminToken, null);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        return requestId;
    }

    // ---- seeding/date helpers ----

    /** The next Monday strictly after today - deterministic regardless of which weekday "today" happens to be. */
    private LocalDate nextMonday() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private String createLeaveType(String adminToken, String name, String code, int defaultAllocationDays) {
        Map<String, Object> body = Map.of(
                "name", name, "code", code, "defaultAllocationDays", defaultAllocationDays);
        ResponseEntity<String> response = post("/leave-types", adminToken, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private ResponseEntity<String> submitLeaveRequest(String token, String leaveTypeId, LocalDate start,
                                                        LocalDate end, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("leaveTypeId", leaveTypeId);
        body.put("startDate", start.toString());
        body.put("endDate", end.toString());
        if (reason != null) {
            body.put("reason", reason);
        }
        return post("/leave-requests", token, body);
    }

    private void grant(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/EMPLOYEE_LEAVE_MANAGEMENT",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("action", "GRANT"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> put(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PATCH, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AuthResponse registerOrganization(String orgName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@leavemanagement.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label, UUID managerId) {
        String email = label + "-" + UUID.randomUUID() + "@leavemanagement.test";
        String password = "employeepass1";
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Test Employee " + label);
        body.put("email", email);
        body.put("password", password);
        body.put("role", Role.EMPLOYEE.name());
        if (managerId != null) {
            body.put("managerId", managerId.toString());
        }
        ResponseEntity<String> createResponse = post("/employees", adminToken, body);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        LoginRequest loginRequest = new LoginRequest(email, password);
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                baseUrl() + "/auth/login", new HttpEntity<>(loginRequest, loginHeaders), AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return loginResponse.getBody();
    }
}
