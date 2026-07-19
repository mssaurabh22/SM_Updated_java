package com.salesmanager.crm.attendance;

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
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * Part B.4 of the Employee Entitlement plan - clock-in/clock-out attendance, and the derived
 * per-day AttendanceStatus (Present/Absent/On Leave/Holiday/Weekend). Covers: clock-in/clock-out
 * lifecycle (idempotent-rejection of a second same-day clock-in, clock-out requiring a prior
 * clock-in), every branch of the derivation priority order (present overrides everything, then
 * on-leave, then holiday, then weekend, else absent), cross-tenant isolation, and entitlement
 * gating. Follows the same Testcontainers/helper-method style as LeaveManagementIT/SchedulerIT.
 */
class AttendanceIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void nonEntitledOrg_getsForbiddenFeatureNotEntitled_onClockIn() {
        AuthResponse admin = registerOrganization("Attendance Gating Org");
        // Deliberately NOT granted - proves the endpoint 403s before any business logic runs.
        ResponseEntity<String> response = post("/attendance/clock-in", admin.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(response.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
    }

    @Test
    void clockIn_createsTodaysRecord_withCheckInTimestamp_noCheckOutYet() {
        AuthResponse admin = registerOrganization("Attendance ClockIn Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "clockInEmployee", null);

        ResponseEntity<String> response = post("/attendance/clock-in", employee.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("attendanceDate").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(body.get("checkInAt").isNull()).isFalse();
        assertThat(body.get("checkOutAt").isNull()).isTrue();
    }

    @Test
    void secondClockInSameDay_isRejectedWithConflict_originalCheckInTimeUnchanged() {
        AuthResponse admin = registerOrganization("Attendance Double ClockIn Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "doubleClockInEmployee", null);

        ResponseEntity<String> first = post("/attendance/clock-in", employee.accessToken());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstCheckInAt = parse(first.getBody()).get("checkInAt").asText();

        ResponseEntity<String> second = post("/attendance/clock-in", employee.accessToken());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The original check-in record/timestamp is untouched by the rejected second attempt.
        // Compared as instants, not raw strings: the create response serializes checkInAt in
        // the JVM's default zone offset, while a value round-tripped through the timestamptz
        // column comes back normalized to UTC ("Z") - same instant, different ISO-8601 offset
        // notation, so a string-equality check would be a false failure.
        // Truncated to millis before comparing: the create response holds the in-memory,
        // nanosecond-precision OffsetDateTime.now() value, while a value round-tripped through
        // the timestamptz column (microsecond precision) comes back very slightly rounded -
        // same instant for any practical purpose, but not bit-for-bit equal past the
        // microsecond digit, so exact equality (even offset-agnostic via #isEqual) is too
        // strict here.
        JsonNode mineAfter = parse(get("/attendance/mine", employee.accessToken()).getBody());
        JsonNode today = dayEntry(mineAfter, LocalDate.now());
        assertThat(OffsetDateTime.parse(today.get("checkInAt").asText()).truncatedTo(ChronoUnit.MILLIS)
                .isEqual(OffsetDateTime.parse(firstCheckInAt).truncatedTo(ChronoUnit.MILLIS))).isTrue();
    }

    @Test
    void clockOut_withoutPriorClockIn_isRejectedWithConflict() {
        AuthResponse admin = registerOrganization("Attendance ClockOut NoClockIn Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "clockOutNoClockInEmployee", null);

        ResponseEntity<String> response = post("/attendance/clock-out", employee.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void clockOut_afterClockIn_setsCheckOutAt() {
        AuthResponse admin = registerOrganization("Attendance ClockOut Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "clockOutEmployee", null);

        assertThat(post("/attendance/clock-in", employee.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> response = post("/attendance/clock-out", employee.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("checkInAt").isNull()).isFalse();
        assertThat(body.get("checkOutAt").isNull()).isFalse();
    }

    @Test
    void monthCalendar_derivesPresentOnLeaveHolidayWeekendAbsent_inPriorityOrder() {
        AuthResponse admin = registerOrganization("Attendance Calendar Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "calendarEmployee", null);

        YearMonth currentMonth = YearMonth.now();
        LocalDate today = LocalDate.now();
        LocalDate[] weekend = firstFullWeekendInMonth(currentMonth);
        LocalDate saturday = weekend[0];
        LocalDate sunday = weekend[1];
        List<LocalDate> weekdays = distinctWeekdaysExcluding(currentMonth, 3, today, saturday, sunday);
        LocalDate holidayDate = weekdays.get(0);
        LocalDate leaveDate = weekdays.get(1);
        LocalDate absentDate = weekdays.get(2);

        // Present: employee clocks in today.
        assertThat(post("/attendance/clock-in", employee.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Holiday: an org holiday landing on holidayDate.
        Map<String, Object> holidayBody = Map.of("name", "Calendar Test Holiday", "holidayDate", holidayDate.toString());
        assertThat(post("/holidays", admin.accessToken(), holidayBody).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // On Leave: an APPROVED leave request covering leaveDate (no manager set on this
        // employee, so it routes to - and is approved by - the Admin fallback pool).
        String leaveTypeId = createLeaveType(admin.accessToken(), "Calendar Leave Type", "CALENDAR_LV", 10);
        Map<String, Object> leaveRequestBody = new HashMap<>();
        leaveRequestBody.put("leaveTypeId", leaveTypeId);
        leaveRequestBody.put("startDate", leaveDate.toString());
        leaveRequestBody.put("endDate", leaveDate.toString());
        ResponseEntity<String> leaveSubmitted = post("/leave-requests", employee.accessToken(), leaveRequestBody);
        assertThat(leaveSubmitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leaveRequestId = parse(leaveSubmitted.getBody()).get("id").asText();
        assertThat(patch("/leave-requests/" + leaveRequestId + "/approve", admin.accessToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // absentDate is left completely untouched: a working day, no clock-in, no leave, no holiday.

        JsonNode mine = parse(get("/attendance/mine", employee.accessToken()).getBody());
        assertThat(dayEntry(mine, today).get("status").asText()).isEqualTo("PRESENT");
        assertThat(dayEntry(mine, saturday).get("status").asText()).isEqualTo("WEEKEND");
        assertThat(dayEntry(mine, sunday).get("status").asText()).isEqualTo("WEEKEND");
        assertThat(dayEntry(mine, holidayDate).get("status").asText()).isEqualTo("HOLIDAY");
        assertThat(dayEntry(mine, leaveDate).get("status").asText()).isEqualTo("ON_LEAVE");
        assertThat(dayEntry(mine, absentDate).get("status").asText()).isEqualTo("ABSENT");
    }

    @Test
    void crossTenantIsolation_secondOrgCannotSeeFirstOrgsAttendance() {
        AuthResponse orgA = registerOrganization("Attendance Isolation Org A");
        AuthResponse orgB = registerOrganization("Attendance Isolation Org B");
        grant(orgA.orgId());
        grant(orgB.orgId());
        AuthResponse employeeA = createAndLoginEmployee(orgA.accessToken(), "isolationEmployeeA", null);

        assertThat(post("/attendance/clock-in", employeeA.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Org B's Admin querying Org A's employee id by guess never sees Org A's actual
        // check-in - the Hibernate tenantFilter/RLS scope the underlying AttendanceRecord query
        // to Org B, so no matching row is ever found regardless of the employeeId parameter.
        ResponseEntity<String> crossOrgView = get("/attendance/employee/" + employeeA.employeeId(), orgB.accessToken());
        assertThat(crossOrgView.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode crossOrgBody = parse(crossOrgView.getBody());
        assertThat(dayEntry(crossOrgBody, LocalDate.now()).get("status").asText()).isNotEqualTo("PRESENT");
    }

    @Test
    void employeeForAttendance_isAdminOnly_forbiddenForPlainEmployee() {
        AuthResponse admin = registerOrganization("Attendance Admin Only Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "adminOnlyEmployee", null);
        AuthResponse otherEmployee = createAndLoginEmployee(admin.accessToken(), "adminOnlyOtherEmployee", null);

        assertThat(get("/attendance/employee/" + employee.employeeId(), otherEmployee.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/attendance/employee/" + employee.employeeId(), admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- date-picking helpers (deterministic regardless of which month/weekday "today" is) ----

    /** The first Saturday in {@code month} whose following Sunday still falls within the same month. */
    private LocalDate[] firstFullWeekendInMonth(YearMonth month) {
        LocalDate date = month.atDay(1);
        LocalDate lastPossibleSaturday = month.atEndOfMonth().minusDays(1);
        while (!date.isAfter(lastPossibleSaturday)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
                return new LocalDate[] {date, date.plusDays(1)};
            }
            date = date.plusDays(1);
        }
        throw new IllegalStateException("No full weekend found in " + month);
    }

    /** {@code count} distinct Mon-Fri dates in {@code month}, none equal to any of {@code excluded}. */
    private List<LocalDate> distinctWeekdaysExcluding(YearMonth month, int count, LocalDate... excluded) {
        Set<LocalDate> excludedSet = new HashSet<>(Arrays.asList(excluded));
        List<LocalDate> result = new ArrayList<>();
        LocalDate date = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        while (!date.isAfter(end) && result.size() < count) {
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            boolean isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
            if (!isWeekend && !excludedSet.contains(date)) {
                result.add(date);
                excludedSet.add(date);
            }
            date = date.plusDays(1);
        }
        if (result.size() < count) {
            throw new IllegalStateException("Not enough distinct weekdays found in " + month);
        }
        return result;
    }

    private JsonNode dayEntry(JsonNode days, LocalDate date) {
        for (JsonNode day : days) {
            if (day.get("date").asText().equals(date.toString())) {
                return day;
            }
        }
        throw new IllegalStateException("No day entry found for " + date);
    }

    // ---- seeding/REST helpers ----

    private String createLeaveType(String adminToken, String name, String code, int defaultAllocationDays) {
        Map<String, Object> body = Map.of(
                "name", name, "code", code, "defaultAllocationDays", defaultAllocationDays);
        ResponseEntity<String> response = post("/leave-types", adminToken, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
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

    private ResponseEntity<String> post(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@attendance.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label, UUID managerId) {
        String email = label + "-" + UUID.randomUUID() + "@attendance.test";
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
