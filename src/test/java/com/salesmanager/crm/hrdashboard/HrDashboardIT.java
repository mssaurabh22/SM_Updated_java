package com.salesmanager.crm.hrdashboard;

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
import java.util.HashMap;
import java.util.Map;
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
 * Plan B.5's HR overview dashboard - GET /hr-dashboard/today-snapshot and
 * GET /hr-dashboard/leave-utilization. Covers: a manager's scope (their subordinate chain only,
 * via employee.EmployeeHierarchyService) vs. an ADMIN's scope (whole org), pendingApprovalsForMe
 * being the caller's own personal inbox (matching GET /leave-requests/pending-approval exactly,
 * not team-scoped), leave-utilization's scope-wide average (including zero-usage employees in
 * the denominator), an individual contributor with no reports getting an all-zero (not erroring)
 * result on both endpoints, and entitlement gating.
 *
 * notClockedInToday is inherently day-of-week sensitive (AttendanceService#countAbsentToday
 * short-circuits to 0 on a Weekend, which outranks Absent in the derivation priority) - rather
 * than picking a fixed future date (attendance is always self-service/"today", there is no way
 * to clock in for an arbitrary date), this test computes its expectation from today's actual
 * DayOfWeek, exactly mirroring the production rule, so the assertion is correct regardless of
 * which day the suite happens to run on.
 */
class HrDashboardIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void todaySnapshot_managerScopedToTeam_adminScopedToWholeOrg_pendingApprovalsMatchesDirectEndpoint() {
        AuthResponse admin = registerOrganization("HR Snapshot Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "hrManager", null);
        AuthResponse reportOnLeave = createAndLoginEmployee(admin.accessToken(), "hrReportOnLeave", manager.employeeId());
        AuthResponse reportClockedIn = createAndLoginEmployee(admin.accessToken(), "hrReportClockedIn", manager.employeeId());
        AuthResponse reportAbsent = createAndLoginEmployee(admin.accessToken(), "hrReportAbsent", manager.employeeId());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "hrUnrelated", null);

        String leaveTypeId = createLeaveType(admin.accessToken(), "HR Snapshot Leave", "HR_SNAP_LV", 10);

        LocalDate today = LocalDate.now();
        // A 3-calendar-day span starting today always contains at least one working day
        // (weekends only ever occur in pairs) so submission never hard-blocks on "no working
        // days in range" regardless of what weekday "today" happens to be, while still
        // genuinely covering today for the on-leave-today overlap check.
        LocalDate spanEnd = today.plusDays(2);
        submitAndApprove(admin.accessToken(), reportOnLeave.accessToken(), leaveTypeId, today, spanEnd);
        // An unrelated employee's approved leave today too - must count for the ADMIN's
        // whole-org view but never leak into the manager's team-scoped view.
        submitAndApprove(admin.accessToken(), unrelated.accessToken(), leaveTypeId, today, spanEnd);

        assertThat(post("/attendance/clock-in", reportClockedIn.accessToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // reportAbsent deliberately does nothing - neither on leave nor clocked in today.

        // A separate, still-PENDING request from reportOnLeave, routed to the manager - checked
        // against pendingApprovalsForMe below. Must be strictly after spanEnd: nextMonday()
        // alone can land ON spanEnd (e.g. when "today" is a Saturday, spanEnd = today+2 is
        // itself the following Monday), which would collide with the first request above.
        LocalDate futureMonday = nextMonday();
        while (!futureMonday.isAfter(spanEnd)) {
            futureMonday = futureMonday.plusDays(7);
        }
        ResponseEntity<String> pendingSubmit =
                submitLeaveRequest(reportOnLeave.accessToken(), leaveTypeId, futureMonday, futureMonday, null);
        assertThat(pendingSubmit.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        boolean isWorkingDay = today.getDayOfWeek() != DayOfWeek.SATURDAY && today.getDayOfWeek() != DayOfWeek.SUNDAY;

        // ---- Manager's scope: {reportOnLeave, reportClockedIn, reportAbsent} ----
        JsonNode managerSnapshot = parse(get("/hr-dashboard/today-snapshot", manager.accessToken()).getBody());
        assertThat(managerSnapshot.get("onLeaveToday").asInt()).isEqualTo(1); // reportOnLeave only, not unrelated
        assertThat(managerSnapshot.get("notClockedInToday").asInt()).isEqualTo(isWorkingDay ? 1 : 0); // reportAbsent
        int managerPendingViaEndpoint =
                parse(get("/leave-requests/pending-approval", manager.accessToken()).getBody()).size();
        assertThat(managerSnapshot.get("pendingApprovalsForMe").asInt()).isEqualTo(managerPendingViaEndpoint);
        assertThat(managerPendingViaEndpoint).isEqualTo(1);

        // ---- Admin's scope: every active employee in the org ----
        JsonNode adminSnapshot = parse(get("/hr-dashboard/today-snapshot", admin.accessToken()).getBody());
        assertThat(adminSnapshot.get("onLeaveToday").asInt()).isEqualTo(2); // reportOnLeave + unrelated
        // admin, manager, and reportAbsent are all neither on leave nor clocked in today.
        assertThat(adminSnapshot.get("notClockedInToday").asInt()).isEqualTo(isWorkingDay ? 3 : 0);
        int adminPendingViaEndpoint =
                parse(get("/leave-requests/pending-approval", admin.accessToken()).getBody()).size();
        assertThat(adminSnapshot.get("pendingApprovalsForMe").asInt()).isEqualTo(adminPendingViaEndpoint);
    }

    @Test
    void todaySnapshot_individualContributorWithNoReports_getsAllZeroScopedResult_notAnError() {
        AuthResponse admin = registerOrganization("HR Snapshot IC Org");
        grant(admin.orgId());
        AuthResponse ic = createAndLoginEmployee(admin.accessToken(), "hrIc", null);

        JsonNode icSnapshot = parse(get("/hr-dashboard/today-snapshot", ic.accessToken()).getBody());
        assertThat(icSnapshot.get("onLeaveToday").asInt()).isEqualTo(0);
        assertThat(icSnapshot.get("notClockedInToday").asInt()).isEqualTo(0);
        assertThat(icSnapshot.get("pendingApprovalsForMe").asInt()).isEqualTo(0);
    }

    @Test
    void leaveUtilization_averagesAcrossScope_includingZeroUsageEmployees_emptyScopeReturnsAllZero() {
        AuthResponse admin = registerOrganization("HR Utilization Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "utilManager", null);
        AuthResponse reportUsed = createAndLoginEmployee(admin.accessToken(), "utilReportUsed", manager.employeeId());
        createAndLoginEmployee(admin.accessToken(), "utilReportUnused", manager.employeeId());
        AuthResponse ic = createAndLoginEmployee(admin.accessToken(), "utilIc", null);

        String usedLeaveTypeId = createLeaveType(admin.accessToken(), "Utilization Leave Used", "UTIL_LV_USED", 10);
        String unusedLeaveTypeId = createLeaveType(admin.accessToken(), "Utilization Leave Unused", "UTIL_LV_UNUSED", 10);

        LocalDate monday = nextMonday();
        LocalDate wednesday = monday.plusDays(2); // Mon/Tue/Wed - 3 working days, no weekend in between.
        submitAndApprove(admin.accessToken(), reportUsed.accessToken(), usedLeaveTypeId, monday, wednesday);

        int year = monday.getYear();
        JsonNode managerUtilization =
                parse(get("/hr-dashboard/leave-utilization?year=" + year, manager.accessToken()).getBody());
        boolean foundUsed = false;
        boolean foundUnused = false;
        for (JsonNode entry : managerUtilization) {
            // scope = {reportUsed, utilReportUnused} = 2 employees -> 3 days / 2 = 1.5 average.
            if (entry.get("leaveTypeId").asText().equals(usedLeaveTypeId)) {
                assertThat(entry.get("averageDaysUsed").asDouble()).isEqualTo(1.5);
                foundUsed = true;
            }
            if (entry.get("leaveTypeId").asText().equals(unusedLeaveTypeId)) {
                assertThat(entry.get("averageDaysUsed").asDouble()).isEqualTo(0.0);
                foundUnused = true;
            }
        }
        assertThat(foundUsed).isTrue();
        assertThat(foundUnused).isTrue();

        // An individual contributor with no reports has an empty scope - every leave type's
        // average is 0, not an error / not a divide-by-zero failure.
        JsonNode icUtilization = parse(get("/hr-dashboard/leave-utilization?year=" + year, ic.accessToken()).getBody());
        assertThat(icUtilization.size()).isGreaterThan(0);
        for (JsonNode entry : icUtilization) {
            assertThat(entry.get("averageDaysUsed").asDouble()).isEqualTo(0.0);
        }
    }

    @Test
    void nonEntitledOrg_getsForbiddenFeatureNotEntitled_onBothEndpoints() {
        AuthResponse admin = registerOrganization("HR Dashboard Gating Org");

        ResponseEntity<String> snapshotResponse = get("/hr-dashboard/today-snapshot", admin.accessToken());
        assertThat(snapshotResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(snapshotResponse.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");

        ResponseEntity<String> utilizationResponse = get("/hr-dashboard/leave-utilization", admin.accessToken());
        assertThat(utilizationResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(utilizationResponse.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
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

    private String submitAndApprove(String adminToken, String employeeToken, String leaveTypeId,
                                     LocalDate start, LocalDate end) {
        ResponseEntity<String> submitted = submitLeaveRequest(employeeToken, leaveTypeId, start, end, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = parse(submitted.getBody()).get("id").asText();
        ResponseEntity<String> approved = patch("/leave-requests/" + requestId + "/approve", adminToken, null);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        return requestId;
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
                "admin-" + UUID.randomUUID() + "@hrdashboard.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label, UUID managerId) {
        String email = label + "-" + UUID.randomUUID() + "@hrdashboard.test";
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
