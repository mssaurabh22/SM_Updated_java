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
import com.salesmanager.crm.security.TenantSessionManager;
import com.salesmanager.crm.visit.VisitRepository;
import com.salesmanager.crm.visit.VisitStatus;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 5: read-only reporting/dashboard endpoints - pipeline-summary (byStatus + byOwner),
 * conversion-rate, and visits-completed-vs-missed (with optional dateFrom/dateTo filtering).
 * All three are ADMIN-only, org-scoped purely via the Hibernate tenantFilter (no manual
 * organizationId filtering), same as every other read in the codebase.
 *
 * MISSED visits can never be set through the ordinary API (VisitService rejects a
 * client-supplied MISSED both on create and on updateStatus - see VisitCrudIT), so this test
 * seeds one directly via VisitRepository under an explicitly-activated TenantSessionManager
 * transaction (the same tenant-activation mechanism the request-handling TenantFilter and the
 * Phase 4 scheduled jobs use), rather than depending on the concurrently-developed Phase 4
 * scheduler package to produce one.
 */
class ReportingIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private TenantSessionManager tenantSessionManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void pipelineSummary_conversionRate_andVisitsReport_matchSeededData_andEmployeeGets403() {
        AuthResponse admin = registerOrganization("Reporting Org", "Report Admin");
        AuthResponse ownerA = createAndLoginEmployee(admin.accessToken(), "reportOwnerA");
        AuthResponse ownerB = createAndLoginEmployee(admin.accessToken(), "reportOwnerB");
        Masters masters = loadMasters(admin.accessToken());
        String lostReasonId = firstMasterId(admin.accessToken(), MasterType.LOST_REASON);

        // ---- Leads: 8 total, spread across every status and across 3 distinct owners ----
        String lead1 = createLead(admin.accessToken(), masters, "Admin Lead 1", "Contact 1", "9000000001"); // NEW
        String lead2 = createLead(admin.accessToken(), masters, "Admin Lead 2", "Contact 2", "9000000002"); // NEW

        String lead3 = createLead(ownerA.accessToken(), masters, "Owner A Lead 1", "Contact 3", "9000000003");
        patchStatus(admin.accessToken(), lead3, "CONTACTED", null);

        String lead4 = createLead(ownerA.accessToken(), masters, "Owner A Lead 2", "Contact 4", "9000000004");
        patchStatus(admin.accessToken(), lead4, "CLOSED_WON", null);

        String lead5 = createLead(ownerA.accessToken(), masters, "Owner A Lead 3", "Contact 5", "9000000005");
        patchStatus(admin.accessToken(), lead5, "CLOSED_WON", null);

        String lead6 = createLead(ownerB.accessToken(), masters, "Owner B Lead 1", "Contact 6", "9000000006");
        patchStatus(admin.accessToken(), lead6, "LOST", lostReasonId);

        String lead7 = createLead(ownerB.accessToken(), masters, "Owner B Lead 2", "Contact 7", "9000000007");
        patchStatus(admin.accessToken(), lead7, "NEGOTIATION", null);

        String lead8 = createLead(ownerB.accessToken(), masters, "Owner B Lead 3", "Contact 8", "9000000008");
        patchStatus(admin.accessToken(), lead8, "LAPSED", null);

        // ---- Visits: attached to lead1, spanning COMPLETED/MISSED/PLANNED across 5 dates ----
        LocalDate today = LocalDate.now();
        String visit1 = createVisit(admin.accessToken(), lead1, today);
        patchVisitStatus(admin.accessToken(), visit1, "COMPLETED");

        String visit2 = createVisit(admin.accessToken(), lead1, today.plusDays(1));
        patchVisitStatus(admin.accessToken(), visit2, "COMPLETED");

        String visit3 = createVisit(admin.accessToken(), lead1, today.plusDays(2));
        markVisitMissed(admin.orgId(), UUID.fromString(visit3));

        String visit4 = createVisit(admin.accessToken(), lead1, today.plusDays(3)); // left PLANNED

        String visit5 = createVisit(admin.accessToken(), lead1, today.plusDays(10));
        patchVisitStatus(admin.accessToken(), visit5, "COMPLETED");

        // ---- pipeline-summary: byStatus includes every LeadStatus (zero-bucket inclusion) ----
        JsonNode pipeline = getJson("/reports/pipeline-summary", admin.accessToken());
        JsonNode byStatus = pipeline.get("byStatus");
        assertThat(byStatus.get("NEW").asLong()).isEqualTo(2);
        assertThat(byStatus.get("CONTACTED").asLong()).isEqualTo(1);
        assertThat(byStatus.get("NEGOTIATION").asLong()).isEqualTo(1);
        assertThat(byStatus.get("LOST").asLong()).isEqualTo(1);
        assertThat(byStatus.get("CLOSED_WON").asLong()).isEqualTo(2);
        assertThat(byStatus.get("LAPSED").asLong()).isEqualTo(1);
        assertThat(byStatus.size()).isEqualTo(6);
        assertThat(pipeline.get("totalLeads").asLong()).isEqualTo(8);

        JsonNode byOwner = pipeline.get("byOwner");
        assertThat(byOwner.size()).isEqualTo(3);
        Map<String, JsonNode> ownerById = new HashMap<>();
        for (JsonNode owner : byOwner) {
            ownerById.put(owner.get("ownerId").asText(), owner);
        }
        JsonNode adminOwner = ownerById.get(admin.employeeId().toString());
        assertThat(adminOwner.get("leadCount").asLong()).isEqualTo(2);
        assertThat(adminOwner.get("closedWonCount").asLong()).isEqualTo(0);
        assertThat(adminOwner.get("ownerName").asText()).isEqualTo("Report Admin");

        JsonNode ownerAEntry = ownerById.get(ownerA.employeeId().toString());
        assertThat(ownerAEntry.get("leadCount").asLong()).isEqualTo(3);
        assertThat(ownerAEntry.get("closedWonCount").asLong()).isEqualTo(2);
        assertThat(ownerAEntry.get("ownerName").asText()).isEqualTo("Test Employee reportOwnerA");

        JsonNode ownerBEntry = ownerById.get(ownerB.employeeId().toString());
        assertThat(ownerBEntry.get("leadCount").asLong()).isEqualTo(3);
        assertThat(ownerBEntry.get("closedWonCount").asLong()).isEqualTo(0);

        // ---- conversion-rate: 2 CLOSED_WON / 8 total = 25.00% exactly ----
        JsonNode conversion = getJson("/reports/conversion-rate", admin.accessToken());
        assertThat(conversion.get("totalLeads").asLong()).isEqualTo(8);
        assertThat(conversion.get("closedWonCount").asLong()).isEqualTo(2);
        assertThat(conversion.get("lostCount").asLong()).isEqualTo(1);
        assertThat(conversion.get("conversionRatePercent").asDouble()).isEqualTo(25.0);

        // ---- visits-completed-vs-missed: unfiltered covers all 5 visits ----
        JsonNode visitsAll = getJson("/reports/visits-completed-vs-missed", admin.accessToken());
        assertThat(visitsAll.get("completed").asLong()).isEqualTo(3);
        assertThat(visitsAll.get("missed").asLong()).isEqualTo(1);
        assertThat(visitsAll.get("planned").asLong()).isEqualTo(1);
        // 3 / (3 + 1) * 100 = 75.00
        assertThat(visitsAll.get("completionRatePercent").asDouble()).isEqualTo(75.0);

        // ---- visits-completed-vs-missed: dateFrom/dateTo excludes visit5 (today + 10) ----
        String rangeQuery = "/reports/visits-completed-vs-missed?dateFrom=" + today + "&dateTo=" + today.plusDays(3);
        JsonNode visitsRanged = getJson(rangeQuery, admin.accessToken());
        assertThat(visitsRanged.get("completed").asLong()).isEqualTo(2);
        assertThat(visitsRanged.get("missed").asLong()).isEqualTo(1);
        assertThat(visitsRanged.get("planned").asLong()).isEqualTo(1);
        // 2 / (2 + 1) * 100 = 66.666... -> rounds to 66.67
        assertThat(visitsRanged.get("completionRatePercent").asDouble()).isEqualTo(66.67);

        // ---- EMPLOYEE role gets 403 on all three endpoints ----
        assertThat(get("/reports/pipeline-summary", ownerA.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/reports/conversion-rate", ownerA.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/reports/visits-completed-vs-missed", ownerA.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // ---- Tenant isolation: seed a second org's data, confirm Org A's figures don't move ----
        AuthResponse otherAdmin = registerOrganization("Reporting Org Two", "Other Admin");
        Masters otherMasters = loadMasters(otherAdmin.accessToken());
        for (int i = 0; i < 5; i++) {
            createLead(otherAdmin.accessToken(), otherMasters, "Other Org Lead " + i, "Other Contact " + i,
                    "9100000" + String.format("%03d", i));
        }
        String otherLeadForVisit = createLead(otherAdmin.accessToken(), otherMasters, "Other Org Visit Lead",
                "Other Visit Contact", "9199999999");
        String otherVisit = createVisit(otherAdmin.accessToken(), otherLeadForVisit, today);
        patchVisitStatus(otherAdmin.accessToken(), otherVisit, "COMPLETED");

        JsonNode pipelineAfter = getJson("/reports/pipeline-summary", admin.accessToken());
        assertThat(pipelineAfter.get("totalLeads").asLong()).isEqualTo(8);
        assertThat(pipelineAfter.get("byStatus").get("NEW").asLong()).isEqualTo(2);
        assertThat(pipelineAfter.get("byOwner").size()).isEqualTo(3);

        JsonNode conversionAfter = getJson("/reports/conversion-rate", admin.accessToken());
        assertThat(conversionAfter.get("totalLeads").asLong()).isEqualTo(8);
        assertThat(conversionAfter.get("conversionRatePercent").asDouble()).isEqualTo(25.0);

        JsonNode visitsAfter = getJson("/reports/visits-completed-vs-missed", admin.accessToken());
        assertThat(visitsAfter.get("completed").asLong()).isEqualTo(3);
        assertThat(visitsAfter.get("missed").asLong()).isEqualTo(1);
        assertThat(visitsAfter.get("planned").asLong()).isEqualTo(1);

        // And the other org's own pipeline-summary reflects only its own 6 leads (5 + 1), not Org A's 8.
        JsonNode otherPipeline = getJson("/reports/pipeline-summary", otherAdmin.accessToken());
        assertThat(otherPipeline.get("totalLeads").asLong()).isEqualTo(6);
        assertThat(otherPipeline.get("byStatus").get("NEW").asLong()).isEqualTo(6);
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

    private void patchStatus(String token, String leadId, String status, String lostReasonId) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        if (lostReasonId != null) {
            body.put("lostReasonId", lostReasonId);
        }
        ResponseEntity<String> response = patch("/leads/" + leadId + "/status", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private void patchVisitStatus(String token, String visitId, String status) {
        ResponseEntity<String> response = patch("/visits/" + visitId + "/status", token, Map.of("status", status));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Directly flips a Visit to MISSED via VisitRepository, bypassing VisitService's
     * client-supplied-MISSED rejection - see this class's javadoc for why the ordinary API
     * cannot produce a MISSED visit for test seeding. Runs inside its own explicit transaction
     * (via TransactionTemplate, since this test thread has none of its own - all the HTTP calls
     * above run on the embedded server's threads) with the tenantFilter activated for orgId,
     * exactly like TenantFilter does for a normal request.
     */
    private void markVisitMissed(UUID orgId, UUID visitId) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            tenantSessionManager.activateTenant(orgId);
            try {
                var visit = visitRepository.findById(visitId).orElseThrow();
                visit.setStatus(VisitStatus.MISSED);
                visitRepository.saveAndFlush(visit);
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private JsonNode getJson(String path, String token) {
        ResponseEntity<String> response = get(path, token);
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
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, authEntity(token), String.class);
    }

    private HttpEntity<Void> authEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AuthResponse registerOrganization(String orgName, String adminFullName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), adminFullName,
                "admin-" + UUID.randomUUID() + "@reportingit.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@reportingit.test";
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
