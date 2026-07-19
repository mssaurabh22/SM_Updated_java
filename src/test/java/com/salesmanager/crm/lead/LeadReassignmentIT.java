package com.salesmanager.crm.lead;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.masterdata.MasterType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Phase 3: Lead reassignment (ADMIN-only) and the LEAD_REASSIGNED notification it creates
 * for the new owner.
 */
class LeadReassignmentIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void adminReassigningLead_updatesOwnerId_andCreatesNotificationForNewOwner() {
        AuthResponse admin = registerOrganization("Reassignment Org");
        AuthResponse originalOwner = createAndLoginEmployee(admin.accessToken(), "reassignOriginal");
        AuthResponse newOwner = createAndLoginEmployee(admin.accessToken(), "reassignNew");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Reassignment Co", "Contact R", "9777777701");
        ResponseEntity<String> created = post("/leads", originalOwner.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leadId = parse(created.getBody()).get("id").asText();

        ResponseEntity<String> reassignResponse = patch("/leads/" + leadId + "/reassign", admin.accessToken(),
                Map.of("newOwnerId", newOwner.employeeId().toString()));
        assertThat(reassignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode reassigned = parse(reassignResponse.getBody());
        assertThat(reassigned.get("ownerId").asText()).isEqualTo(newOwner.employeeId().toString());

        // The new owner now sees a LEAD_REASSIGNED notification.
        JsonNode notifications = getNotifications(newOwner.accessToken(), false);
        assertThat(notifications.get("content").size()).isEqualTo(1);
        JsonNode notification = notifications.get("content").get(0);
        assertThat(notification.get("type").asText()).isEqualTo("LEAD_REASSIGNED");
        assertThat(notification.get("read").asBoolean()).isFalse();
        assertThat(notification.get("payload").asText()).contains(leadId).contains("Reassignment Co");

        // The original owner (no longer the owner) has no such notification.
        JsonNode originalOwnerNotifications = getNotifications(originalOwner.accessToken(), false);
        assertThat(originalOwnerNotifications.get("content").size()).isEqualTo(0);

        // Marking it read works and is reflected in the unreadOnly filter.
        String notificationId = notification.get("id").asText();
        ResponseEntity<String> markRead = patch("/notifications/" + notificationId + "/read", newOwner.accessToken(),
                null);
        assertThat(markRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(markRead.getBody()).get("read").asBoolean()).isTrue();

        JsonNode unreadAfter = getNotifications(newOwner.accessToken(), true);
        assertThat(unreadAfter.get("content").size()).isEqualTo(0);
    }

    @Test
    void employeeAttemptingReassignment_getsForbidden() {
        AuthResponse admin = registerOrganization("Reassignment Forbidden Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "reassignForbidden");
        AuthResponse anotherEmployee = createAndLoginEmployee(admin.accessToken(), "reassignForbiddenTarget");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Forbidden Reassign Co", "Contact FR", "9777777702");
        ResponseEntity<String> created = post("/leads", employee.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leadId = parse(created.getBody()).get("id").asText();

        ResponseEntity<String> attempt = patch("/leads/" + leadId + "/reassign", employee.accessToken(),
                Map.of("newOwnerId", anotherEmployee.employeeId().toString()));
        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- helpers ----

    private record Masters(String cityId, String leadSourceId, String industryId) {
    }

    private Masters loadMasters(String adminToken) {
        return new Masters(
                firstMasterId(adminToken, MasterType.CITY),
                firstMasterId(adminToken, MasterType.LEAD_SOURCE),
                firstMasterId(adminToken, MasterType.INDUSTRY));
    }

    private Map<String, Object> minimalLeadBody(Masters masters, String companyName, String contactPerson,
                                                 String contactNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", companyName);
        body.put("contactPerson", contactPerson);
        body.put("contactNo", contactNo);
        body.put("cityId", masters.cityId);
        body.put("leadSourceId", masters.leadSourceId);
        body.put("industryId", masters.industryId);
        return body;
    }

    private String firstMasterId(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(response.getBody());
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
    }

    private JsonNode getNotifications(String token, boolean unreadOnly) {
        ResponseEntity<String> response = get("/notifications?unreadOnly=" + unreadOnly, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
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
                "admin-" + UUID.randomUUID() + "@reassign.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@reassign.test";
        String password = "employeepass1";
        Map<String, Object> body = Map.of(
                "fullName", "Test Employee " + label,
                "email", email,
                "password", password,
                "role", Role.EMPLOYEE.name());
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
