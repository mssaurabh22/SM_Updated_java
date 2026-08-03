package com.salesmanager.crm.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.masterdata.MasterType;
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
 * GET /reports/team-progress: a read-only per-team-member rollup (lead counts by status, visits
 * due today/upcoming, last activity). Reuses the same TEAM_VISIBILITY-gated scoping rule as
 * every other reporting endpoint (see ReportingService#resolveTeamMemberIds), but lists
 * individual members rather than an aggregate - so an ADMIN sees every other active employee,
 * and an entitled manager sees their subordinates (never themself), while an unentitled/
 * report-less EMPLOYEE still gets 403.
 */
class TeamProgressIT extends AbstractIntegrationTest {

    private static final String TEAM_VISIBILITY = "TEAM_VISIBILITY";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void admin_seesEveryOtherActiveEmployee_withLeadStatusAndVisitRollups() {
        AuthResponse admin = registerOrganization("Team Progress Admin Org");
        String employeeAId = createEmployee(admin.accessToken(), "Progress Employee A", null);
        AuthResponse employeeA = login(employeeAId, admin.accessToken());
        String employeeBId = createEmployee(admin.accessToken(), "Progress Employee B", null);
        Masters masters = loadMasters(admin.accessToken());

        String leadId1 = createLead(employeeA.accessToken(), masters, "Progress Co 1", "9600000001");
        createLead(employeeA.accessToken(), masters, "Progress Co 2", "9600000002");
        createVisit(employeeA.accessToken(), leadId1, LocalDate.now());

        JsonNode response = getJson("/reports/team-progress", admin.accessToken());
        JsonNode members = response.get("members");
        assertThat(members.size()).isEqualTo(2);

        JsonNode memberA = findMember(members, employeeAId);
        assertThat(memberA.get("totalLeads").asLong()).isEqualTo(2);
        assertThat(memberA.get("leadCountsByStatus").get("INTERESTED").asLong()).isEqualTo(2);
        assertThat(memberA.get("visitsDueToday").asLong()).isEqualTo(1);
        assertThat(memberA.get("visitsUpcoming").asLong()).isEqualTo(0);
        assertThat(memberA.get("lastActivityAt").isNull()).isFalse();

        JsonNode memberB = findMember(members, employeeBId);
        assertThat(memberB.get("totalLeads").asLong()).isEqualTo(0);
        assertThat(memberB.get("visitsDueToday").asLong()).isEqualTo(0);
        assertThat(memberB.get("lastActivityAt").isNull()).isTrue();

        // The admin's own record never appears in their own team-progress list.
        for (JsonNode member : members) {
            assertThat(member.get("employeeId").asText()).isNotEqualTo(admin.employeeId().toString());
        }
    }

    @Test
    void manager_withTeamVisibilityEntitled_seesOnlySubordinates_neverSelfOrUnrelated() {
        AuthResponse admin = registerOrganization("Team Progress Manager Org");
        String managerId = createEmployee(admin.accessToken(), "Progress Manager", null);
        AuthResponse manager = login(managerId, admin.accessToken());
        String reportId = createEmployee(admin.accessToken(), "Progress Report", managerId);
        AuthResponse report = login(reportId, admin.accessToken());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "progressUnrelated");
        Masters masters = loadMasters(admin.accessToken());

        createLead(manager.accessToken(), masters, "Manager-Owned Co", "9700000001");
        String reportLeadId = createLead(report.accessToken(), masters, "Report-Owned Co", "9700000002");
        createLead(unrelated.accessToken(), masters, "Unrelated-Owned Co", "9700000003");

        // Not entitled yet - 403, same as every other team-scoped reporting endpoint.
        assertThat(get("/reports/team-progress", manager.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        grantTeamVisibility(admin.orgId());

        JsonNode response = getJson("/reports/team-progress", manager.accessToken());
        JsonNode members = response.get("members");
        assertThat(members.size()).isEqualTo(1);
        assertThat(members.get(0).get("employeeId").asText()).isEqualTo(reportId);
        assertThat(members.get(0).get("totalLeads").asLong()).isEqualTo(1);

        // A leaf employee (no reports of their own) still gets 403 even when entitled.
        assertThat(get("/reports/team-progress", unrelated.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(reportLeadId).isNotBlank();
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

    private JsonNode findMember(JsonNode members, String employeeId) {
        for (JsonNode member : members) {
            if (member.get("employeeId").asText().equals(employeeId)) {
                return member;
            }
        }
        throw new IllegalStateException("No team-progress member found for employeeId " + employeeId);
    }

    private String createLead(String token, Masters masters, String companyName, String contactNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", companyName);
        body.put("contactPerson", "Contact " + contactNo);
        body.put("contactNo", contactNo);
        body.put("cityId", masters.cityId);
        body.put("leadSourceId", masters.leadSourceId);
        body.put("industryId", masters.industryId);
        body.put("logAsVisitToday", false);
        ResponseEntity<String> response = post("/leads", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private String createVisit(String token, String leadId, LocalDate visitDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("leadId", leadId);
        body.put("visitDate", visitDate.toString());
        body.put("visitType", "FIELD");
        ResponseEntity<String> response = post("/visits", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private void grantTeamVisibility(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "TeamProgressIT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + TEAM_VISIBILITY,
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createEmployee(String adminToken, String fullName, String managerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("email", "tp-" + UUID.randomUUID() + "@teamprogress.test");
        body.put("password", "supersecret1");
        body.put("role", Role.EMPLOYEE.name());
        if (managerId != null) {
            body.put("managerId", managerId);
        }
        ResponseEntity<String> response = post("/employees", adminToken, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private AuthResponse login(String employeeId, String adminToken) {
        JsonNode employee = parse(get("/employees/" + employeeId, adminToken).getBody());
        LoginRequest loginRequest = new LoginRequest(employee.get("email").asText(), "supersecret1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/login", new HttpEntity<>(loginRequest, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@teamprogress.test";
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

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode getJson(String path, String token) {
        ResponseEntity<String> response = get(path, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
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
                "admin-" + UUID.randomUUID() + "@teamprogress.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
