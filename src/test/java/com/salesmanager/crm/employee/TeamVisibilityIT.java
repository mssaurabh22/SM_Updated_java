package com.salesmanager.crm.employee;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
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
 * FeatureEntitlement.TEAM_VISIBILITY end-to-end: expands a manager's (an employee with at
 * least one direct/indirect report via Employee.managerId) visibility in Leads/Visits/Reports
 * from "just their own" to "themself + every subordinate at any depth" - see
 * EmployeeHierarchyService#getTeamVisibilityScope's javadoc. Deliberately does NOT re-prove the
 * base owner-scoped visibility rule (an EMPLOYEE never sees an unrelated colleague's data) -
 * that's already covered by LeadCrudIT/VisitCrudIT/ReportingIT; this class only covers what
 * changes once TEAM_VISIBILITY is granted, plus that write paths (update/reassign) stay
 * unaffected - team visibility is a READ-only grant, not an edit right.
 */
class TeamVisibilityIT extends AbstractIntegrationTest {

    private static final String CODE = "TEAM_VISIBILITY";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void leadListAndGetById_managerWithEntitlement_seesOwnAndSubordinatesLeads_onlyWithinScope() {
        AuthResponse admin = registerOrganization("Team Visibility Lead Org");
        String managerId = createEmployee(admin.accessToken(), "Manager M", null);
        AuthResponse manager = login(managerId, admin.accessToken());
        String reportId = createEmployee(admin.accessToken(), "Report R", managerId);
        AuthResponse report = login(reportId, admin.accessToken());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "unrelated");
        Masters masters = loadMasters(admin.accessToken());

        String managerLeadId = createLead(manager.accessToken(), masters, "Manager Co", "M Contact", "9200000001");
        String reportLeadId = createLead(report.accessToken(), masters, "Report Co", "R Contact", "9200000002");
        String unrelatedLeadId = createLead(unrelated.accessToken(), masters, "Unrelated Co", "U Contact", "9200000003");

        // ---- Before granting TEAM_VISIBILITY: manager sees only their own lead, despite having
        // a subordinate - same as the pre-existing owner-only rule. ----
        JsonNode beforeGrant = getJson("/leads", manager.accessToken());
        assertThat(idsOf(beforeGrant)).containsExactly(managerLeadId);
        assertThat(get("/leads/" + reportLeadId, manager.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        grant(admin.orgId());

        // ---- After granting: manager's unfiltered list expands to themself + subordinate,
        // never the unrelated employee's lead. ----
        JsonNode afterGrant = getJson("/leads", manager.accessToken());
        assertThat(idsOf(afterGrant)).containsExactlyInAnyOrder(managerLeadId, reportLeadId);

        // getById now reaches the subordinate's lead too, but still not the unrelated one.
        assertThat(get("/leads/" + reportLeadId, manager.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/leads/" + unrelatedLeadId, manager.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // An ownerId filter naming someone OUTSIDE the manager's scope is never honored as a way
        // to peek at their data - it's ignored, falling back to the manager's own team scope.
        JsonNode filteredOutsideScope = getJson("/leads?ownerId=" + unrelated.employeeId(), manager.accessToken());
        assertThat(idsOf(filteredOutsideScope)).containsExactlyInAnyOrder(managerLeadId, reportLeadId);

        // An ownerId filter naming someone WITHIN scope narrows to just that owner.
        JsonNode filteredWithinScope = getJson("/leads?ownerId=" + report.employeeId(), manager.accessToken());
        assertThat(idsOf(filteredWithinScope)).containsExactly(reportLeadId);

        // TEAM_VISIBILITY is read-only: the manager still cannot edit the subordinate's lead.
        ResponseEntity<String> updateAttempt = put("/leads/" + reportLeadId, manager.accessToken(),
                Map.of("companyName", "Hijacked Name"));
        assertThat(updateAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // The report's own view is unaffected - they're a leaf with no subordinates, so their
        // scope stays empty (self-only) even with the entitlement active.
        JsonNode reportView = getJson("/leads", report.accessToken());
        assertThat(idsOf(reportView)).containsExactly(reportLeadId);
    }

    @Test
    void visitListAndGetById_managerWithEntitlement_scopesThroughSubordinatesLeadOwnership() {
        AuthResponse admin = registerOrganization("Team Visibility Visit Org");
        String managerId = createEmployee(admin.accessToken(), "Visit Manager", null);
        AuthResponse manager = login(managerId, admin.accessToken());
        String reportId = createEmployee(admin.accessToken(), "Visit Report", managerId);
        AuthResponse report = login(reportId, admin.accessToken());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "visitUnrelated");
        Masters masters = loadMasters(admin.accessToken());

        String reportLeadId = createLead(report.accessToken(), masters, "Visit Report Co", "Contact", "9300000001");
        String unrelatedLeadId = createLead(unrelated.accessToken(), masters, "Visit Unrelated Co", "Contact",
                "9300000002");
        LocalDate today = LocalDate.now();
        String reportVisitId = createVisit(report.accessToken(), reportLeadId, today);
        createVisit(unrelated.accessToken(), unrelatedLeadId, today);

        // Before the grant: manager's visit list is empty (no leads of their own, and no
        // visibility into the report's lead's visit yet).
        assertThat(idsOf(getJson("/visits", manager.accessToken()))).isEmpty();
        assertThat(get("/visits/" + reportVisitId, manager.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        grant(admin.orgId());

        JsonNode managerVisits = getJson("/visits", manager.accessToken());
        assertThat(idsOf(managerVisits)).containsExactly(reportVisitId);
        assertThat(get("/visits/" + reportVisitId, manager.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void reports_scopeToManagerTeam_andRemain403ForNonManagersEvenWhenEntitled() {
        AuthResponse admin = registerOrganization("Team Visibility Reports Org");
        String managerId = createEmployee(admin.accessToken(), "Reports Manager", null);
        AuthResponse manager = login(managerId, admin.accessToken());
        String reportId = createEmployee(admin.accessToken(), "Reports Report", managerId);
        AuthResponse report = login(reportId, admin.accessToken());
        AuthResponse unrelated = createAndLoginEmployee(admin.accessToken(), "reportsUnrelated");
        Masters masters = loadMasters(admin.accessToken());

        createLead(manager.accessToken(), masters, "R-Manager Co", "Contact", "9400000001");
        createLead(report.accessToken(), masters, "R-Report Co", "Contact", "9400000002");
        createLead(unrelated.accessToken(), masters, "R-Unrelated Co", "Contact", "9400000003");

        // Not entitled yet: even though this employee has a subordinate, reports stay 403 -
        // matching the pre-existing ADMIN-only behavior.
        assertThat(get("/reports/pipeline-summary", manager.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        grant(admin.orgId());

        // Manager (has a subordinate + entitlement): scoped to themself + report, 2 leads total.
        JsonNode managerPipeline = getJson("/reports/pipeline-summary", manager.accessToken());
        assertThat(managerPipeline.get("totalLeads").asLong()).isEqualTo(2);

        // ADMIN stays unrestricted regardless: all 3 leads.
        JsonNode adminPipeline = getJson("/reports/pipeline-summary", admin.accessToken());
        assertThat(adminPipeline.get("totalLeads").asLong()).isEqualTo(3);

        // A plain individual contributor (no subordinates) still gets 403 even with the org
        // entitled - TEAM_VISIBILITY only helps someone who actually has a team.
        assertThat(get("/reports/pipeline-summary", unrelated.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/reports/conversion-rate", unrelated.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/reports/visits-completed-vs-missed", unrelated.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
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

    private String createVisit(String token, String leadId, LocalDate visitDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("leadId", leadId);
        body.put("visitDate", visitDate.toString());
        body.put("visitType", "FIELD");
        ResponseEntity<String> response = post("/visits", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private java.util.List<String> idsOf(JsonNode pagedResponse) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode node : pagedResponse.get("content")) {
            ids.add(node.get("id").asText());
        }
        return ids;
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "TeamVisibilityIT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + CODE,
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createEmployee(String adminToken, String fullName, String managerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("email", "tv-" + UUID.randomUUID() + "@teamvisibility.test");
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
        String email = label + "-" + UUID.randomUUID() + "@teamvisibility.test";
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

    private ResponseEntity<String> put(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@teamvisibility.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
