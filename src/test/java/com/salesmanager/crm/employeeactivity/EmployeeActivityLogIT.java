package com.salesmanager.crm.employeeactivity;

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
 * The Leave module's parallel, NEW employee-activity history table (plan B.3) - deliberately
 * separate from the Lead-centric activity.ActivityLog (see EmployeeActivityLog's class
 * javadoc). Proves a submit -> approve cycle writes both a LEAVE_REQUEST_SUBMITTED and a
 * LEAVE_REQUEST_APPROVED entry, visible both to an ADMIN (any employeeId filter) and to the
 * employee themself (forced to their own id) via GET /employee-activity.
 */
class EmployeeActivityLogIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void submitApproveCycle_producesSubmittedAndApprovedEntries_visibleToAdminAndToTheEmployee() {
        AuthResponse admin = registerOrganization("Employee Activity Org");
        grant(admin.orgId());
        AuthResponse manager = createAndLoginEmployee(admin.accessToken(), "empActivityManager", null);
        AuthResponse report = createAndLoginEmployee(admin.accessToken(), "empActivityReport", manager.employeeId());

        Map<String, Object> leaveTypeBody = Map.of(
                "name", "Activity Leave", "code", "ACTIVITY_LV", "defaultAllocationDays", 10);
        ResponseEntity<String> leaveTypeCreated = post("/leave-types", admin.accessToken(), leaveTypeBody);
        assertThat(leaveTypeCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leaveTypeId = parse(leaveTypeCreated.getBody()).get("id").asText();

        LocalDate monday = nextMonday();
        LocalDate tuesday = monday.plusDays(1);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("leaveTypeId", leaveTypeId);
        requestBody.put("startDate", monday.toString());
        requestBody.put("endDate", tuesday.toString());
        ResponseEntity<String> submitted = post("/leave-requests", report.accessToken(), requestBody);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = parse(submitted.getBody()).get("id").asText();

        ResponseEntity<String> approved = patch("/leave-requests/" + requestId + "/approve", manager.accessToken());
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);

        // As ADMIN, filtering explicitly by the report's employeeId.
        JsonNode adminView = activityContent(admin.accessToken(), "?employeeId=" + report.employeeId());
        assertThat(hasType(adminView, "LEAVE_REQUEST_SUBMITTED")).isTrue();
        assertThat(hasType(adminView, "LEAVE_REQUEST_APPROVED")).isTrue();

        // As the Report themself - employeeId filter is forced to their own id regardless.
        JsonNode reportView = activityContent(report.accessToken(), "");
        assertThat(hasType(reportView, "LEAVE_REQUEST_SUBMITTED")).isTrue();
        assertThat(hasType(reportView, "LEAVE_REQUEST_APPROVED")).isTrue();
        for (JsonNode entry : reportView) {
            assertThat(entry.get("employeeId").asText()).isEqualTo(report.employeeId().toString());
        }
    }

    // ---- helpers ----

    private boolean hasType(JsonNode entries, String type) {
        for (JsonNode entry : entries) {
            if (entry.get("type").asText().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private LocalDate nextMonday() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private JsonNode activityContent(String token, String query) {
        ResponseEntity<String> response = get("/employee-activity" + query, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("content");
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

    private ResponseEntity<String> patch(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PATCH, new HttpEntity<>(headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@employeeactivity.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label, UUID managerId) {
        String email = label + "-" + UUID.randomUUID() + "@employeeactivity.test";
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
