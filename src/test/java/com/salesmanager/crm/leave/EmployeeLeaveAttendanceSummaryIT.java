package com.salesmanager.crm.leave;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
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
 * Plan B.5's "Employee Leave & Attendance detail page" backing endpoint
 * (GET /employees/{employeeId}/leave-attendance-summary) - a thin composition of existing
 * leave-balance/leave-request-history/attendance-summary data, gated to the employee themselves,
 * their direct manager, or any Admin (anyone else gets an information-hiding 404). Follows the
 * same Testcontainers/helper-method style as LeaveManagementIT/AttendanceIT.
 */
class EmployeeLeaveAttendanceSummaryIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void employeeSeesOwnSummary_managerSeesReportsSummary_adminSeesAnyones_unrelatedEmployeeGets404() {
        AuthResponse admin = registerOrganization("Leave Attendance Summary Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "summaryManager", null);
        AuthResponse report = createAndLoginEmployee(admin.accessToken(), "summaryReport", manager.employeeId());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "summaryUnrelated", null);

        String path = "/employees/" + report.employeeId() + "/leave-attendance-summary";

        // The employee themselves.
        ResponseEntity<String> self = get(path, report.accessToken());
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode selfBody = parse(self.getBody());
        assertThat(selfBody.has("balances")).isTrue();
        assertThat(selfBody.has("recentRequests")).isTrue();
        assertThat(selfBody.has("attendanceSummary")).isTrue();

        // Their direct manager.
        ResponseEntity<String> byManager = get(path, manager.accessToken());
        assertThat(byManager.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Any Admin.
        ResponseEntity<String> byAdmin = get(path, admin.accessToken());
        assertThat(byAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);

        // An unrelated employee (not the employee, not their manager, not an Admin) - 404,
        // information-hiding, same idiom as LeaveRequestService#decide.
        ResponseEntity<String> byUnrelated = get(path, unrelated.accessToken());
        assertThat(byUnrelated.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void summaryReflectsActualBalanceAndAttendanceData() {
        AuthResponse admin = registerOrganization("Leave Attendance Summary Data Org");
        grant(admin.orgId());
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "summaryDataEmployee", null);
        String leaveTypeId = createLeaveType(admin.accessToken(), "Summary Data Leave", "SUMMARY_DATA_LV", 12);

        assertThat(post("/attendance/clock-in", employee.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> response =
                get("/employees/" + employee.employeeId() + "/leave-attendance-summary", employee.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(response.getBody());

        boolean foundBalance = false;
        for (JsonNode balance : body.get("balances")) {
            if (balance.get("leaveTypeId").asText().equals(leaveTypeId)) {
                assertThat(balance.get("allocatedDays").asDouble()).isEqualTo(12.0);
                foundBalance = true;
            }
        }
        assertThat(foundBalance).isTrue();

        assertThat(body.get("attendanceSummary").get("presentDays").asInt()).isEqualTo(1);
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
                "admin-" + UUID.randomUUID() + "@leaveattendancesummary.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label, UUID managerId) {
        String email = label + "-" + UUID.randomUUID() + "@leaveattendancesummary.test";
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
