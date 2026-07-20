package com.salesmanager.crm.notification;

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
 * GET /notifications/unread-count and PATCH /notifications/read-all - the two additions
 * backing the notification bell's authoritative badge count (previously derived client-side
 * from whatever page the bell happened to have fetched, which under-counted once there were
 * more unread notifications than the bell's fetch size) and the new full Notifications page's
 * "mark all read" action. Reuses LeadReassignmentIT's real LEAD_REASSIGNED-notification trigger
 * to generate seed data rather than inserting rows directly - exercises the actual write path.
 */
class NotificationIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unreadCount_reflectsTotalNotJustFetchedPage_andMarkAllRead_zeroesItOut() {
        AuthResponse admin = registerOrganization("Notification Count Org");
        AuthResponse recipient = createAndLoginEmployee(admin.accessToken(), "notifRecipient");
        AuthResponse originalOwner = createAndLoginEmployee(admin.accessToken(), "notifOriginalOwner");
        Masters masters = loadMasters(admin.accessToken());

        // Three separate reassignments to the same recipient -> three LEAD_REASSIGNED
        // notifications, all unread.
        for (int i = 0; i < 3; i++) {
            String leadId = createLead(originalOwner.accessToken(), masters, "Notif Co " + i, "Contact " + i,
                    "960000000" + i);
            ResponseEntity<String> reassign = patch("/leads/" + leadId + "/reassign", admin.accessToken(),
                    Map.of("newOwnerId", recipient.employeeId().toString()));
            assertThat(reassign.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(unreadCount(recipient.accessToken())).isEqualTo(3);

        // Marking just one read (via the existing per-notification endpoint) drops the count
        // by exactly one - proving unread-count is a real total, not a cached/stale figure.
        JsonNode notifications = getNotifications(recipient.accessToken(), false);
        String firstId = notifications.get("content").get(0).get("id").asText();
        ResponseEntity<String> markOne = patch("/notifications/" + firstId + "/read", recipient.accessToken(), null);
        assertThat(markOne.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unreadCount(recipient.accessToken())).isEqualTo(2);

        // Someone else's unread notifications are entirely unaffected and invisible to this
        // recipient's count.
        assertThat(unreadCount(originalOwner.accessToken())).isEqualTo(0);

        // mark-all-read zeroes out the rest in one call.
        ResponseEntity<String> markAll = patch("/notifications/read-all", recipient.accessToken(), null);
        assertThat(markAll.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unreadCount(recipient.accessToken())).isEqualTo(0);

        JsonNode allAfter = getNotifications(recipient.accessToken(), false);
        for (JsonNode n : allAfter.get("content")) {
            assertThat(n.get("read").asBoolean()).isTrue();
        }

        // No-op, not an error, when there's nothing left unread.
        ResponseEntity<String> markAllAgain = patch("/notifications/read-all", recipient.accessToken(), null);
        assertThat(markAllAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private String firstMasterId(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(response.getBody());
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
    }

    private String createLead(String token, Masters masters, String companyName, String contactPerson,
                               String contactNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", companyName);
        body.put("contactPerson", contactPerson);
        body.put("contactNo", contactNo);
        body.put("cityId", masters.cityId);
        body.put("leadSourceId", masters.leadSourceId);
        body.put("industryId", masters.industryId);
        body.put("logAsVisitToday", false);
        ResponseEntity<String> response = post("/leads", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private long unreadCount(String token) {
        ResponseEntity<String> response = get("/notifications/unread-count", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("count").asLong();
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
                "admin-" + UUID.randomUUID() + "@notificationit.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@notificationit.test";
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
