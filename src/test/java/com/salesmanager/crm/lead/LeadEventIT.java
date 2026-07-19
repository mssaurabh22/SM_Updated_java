package com.salesmanager.crm.lead;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.masterdata.MasterType;
import java.time.LocalDate;
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
 * Phase 3: the event-driven auto-generated-Visit machinery - LeadCreatedEvent's
 * logAsVisitToday behavior and FollowUpScheduledEvent's stub-follow-up-Visit behavior,
 * triggered from both Lead.nextFollowupDate and Visit.nextVisitDate. These all run via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} in a brand-new physical
 * transaction (visit.LeadVisitEventListener) - if that listener failed to reactivate the
 * tenant context before writing, the auto-created Visit would be silently rejected by Postgres
 * RLS rather than throwing, which is exactly what these assertions would catch: a missing row
 * where one is expected, not a stack trace.
 */
class LeadEventIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void logAsVisitToday_true_createsExactlyOneCompletedVisitDatedToday() {
        AuthResponse admin = registerOrganization("Lead Event LogVisit True Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "LogVisit True Co", "Contact LVT", "9666666601");
        body.put("logAsVisitToday", true);
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leadId = parse(created.getBody()).get("id").asText();

        JsonNode visits = getVisitsForLead(admin.accessToken(), leadId);
        assertThat(visits.size()).isEqualTo(1);
        JsonNode visit = visits.get(0);
        assertThat(visit.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(visit.get("visitType").asText()).isEqualTo("FIELD");
        assertThat(visit.get("visitDate").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(visit.get("leadId").asText()).isEqualTo(leadId);
    }

    @Test
    void logAsVisitToday_omitted_defaultsToTrue_createsVisit() {
        // Most leads are entered right after a live interaction, so omitting the flag entirely
        // must behave the same as explicitly sending true - only an EXPLICIT false suppresses
        // the auto-visit (see the next test).
        AuthResponse admin = registerOrganization("Lead Event LogVisit Omitted Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "LogVisit Omitted Co", "Contact LVO", "9666666607");
        // logAsVisitToday deliberately omitted.
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leadId = parse(created.getBody()).get("id").asText();

        JsonNode visits = getVisitsForLead(admin.accessToken(), leadId);
        assertThat(visits.size()).isEqualTo(1);
    }

    @Test
    void logAsVisitToday_explicitFalse_createsNoVisit() {
        AuthResponse admin = registerOrganization("Lead Event LogVisit False Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "LogVisit False Co", "Contact LVF", "9666666602");
        body.put("logAsVisitToday", false);
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leadId = parse(created.getBody()).get("id").asText();

        JsonNode visits = getVisitsForLead(admin.accessToken(), leadId);
        assertThat(visits.size()).isEqualTo(0);
    }

    @Test
    void nextFollowupDate_onCreate_createsExactlyOnePlannedStubVisit_withNoOtherFieldsPopulated() {
        AuthResponse admin = registerOrganization("Lead Event NextFollowup Create Org");
        Masters masters = loadMasters(admin.accessToken());
        LocalDate followUpDate = LocalDate.now().plusDays(5);

        Map<String, Object> body = minimalLeadBody(masters, "NextFollowup Create Co", "Contact NFC", "9666666603");
        body.put("nextFollowupDate", followUpDate.toString());
        // Isolate the nextFollowupDate stub-creation behavior under test from the unrelated
        // logAsVisitToday auto-visit (which now defaults to true) - otherwise this lead would
        // get a second, COMPLETED visit-today alongside the PLANNED stub this test cares about.
        body.put("logAsVisitToday", false);
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leadId = parse(created.getBody()).get("id").asText();

        JsonNode visits = getVisitsForLead(admin.accessToken(), leadId);
        assertThat(visits.size()).isEqualTo(1);
        JsonNode stub = visits.get(0);
        assertThat(stub.get("status").asText()).isEqualTo("PLANNED");
        assertThat(stub.get("visitDate").asText()).isEqualTo(followUpDate.toString());
        assertThat(stub.get("visitType").asText()).isEqualTo("FIELD");
        assertThat(stub.get("purposeId").isNull()).isTrue();
        assertThat(stub.get("contactPerson").isNull()).isTrue();
        assertThat(stub.get("remarks").isNull()).isTrue();
        assertThat(stub.get("objections").isNull()).isTrue();
        assertThat(stub.get("budgetRange").isNull()).isTrue();
    }

    @Test
    void nextFollowupDate_onUpdate_changedToNewValue_createsStubVisit_butNotOnUnrelatedResaveOfSameDate() {
        AuthResponse admin = registerOrganization("Lead Event NextFollowup Update Org");
        Masters masters = loadMasters(admin.accessToken());
        LocalDate followUpDate = LocalDate.now().plusDays(7);

        Map<String, Object> body = minimalLeadBody(masters, "NextFollowup Update Co", "Contact NFU", "9666666604");
        // Isolate the nextFollowupDate stub-creation behavior under test from the unrelated
        // logAsVisitToday auto-visit (which now defaults to true).
        body.put("logAsVisitToday", false);
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        String leadId = parse(created.getBody()).get("id").asText();

        // First update: sets nextFollowupDate to a brand-new value -> exactly one stub Visit.
        ResponseEntity<String> firstUpdate = put("/leads/" + leadId, admin.accessToken(),
                Map.of("nextFollowupDate", followUpDate.toString()));
        assertThat(firstUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode visitsAfterFirst = getVisitsForLead(admin.accessToken(), leadId);
        assertThat(visitsAfterFirst.size()).isEqualTo(1);

        // Second update: resaves the SAME nextFollowupDate alongside an unrelated field change
        // -> must NOT create a second stub Visit.
        ResponseEntity<String> secondUpdate = put("/leads/" + leadId, admin.accessToken(),
                Map.of("nextFollowupDate", followUpDate.toString(), "remarks", "unrelated change"));
        assertThat(secondUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode visitsAfterSecond = getVisitsForLead(admin.accessToken(), leadId);
        assertThat(visitsAfterSecond.size()).isEqualTo(1);
    }

    @Test
    void visitNextVisitDate_onCreate_createsStubFollowUpCarryingOverPurposeId() {
        AuthResponse admin = registerOrganization("Lead Event Visit NextVisit Create Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Visit NextVisit Create Co", "Contact VNC", "9666666605");
        String purposeId = firstMasterId(admin.accessToken(), MasterType.VISIT_PURPOSE);
        LocalDate nextVisitDate = LocalDate.now().plusDays(3);

        Map<String, Object> visitBody = new HashMap<>();
        visitBody.put("leadId", leadId);
        visitBody.put("visitDate", LocalDate.now().toString());
        visitBody.put("visitType", "FIELD");
        visitBody.put("purposeId", purposeId);
        visitBody.put("nextVisitDate", nextVisitDate.toString());
        visitBody.put("remarks", "Went well, following up next week");
        // COMPLETED (not the PLANNED default) so the triggering visit itself doesn't also
        // match the status=PLANNED filter below alongside the auto-created stub.
        visitBody.put("status", "COMPLETED");

        ResponseEntity<String> created = post("/visits", admin.accessToken(), visitBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode plannedVisits = getVisitsForLeadByStatus(admin.accessToken(), leadId, "PLANNED");
        assertThat(plannedVisits.size()).isEqualTo(1);
        JsonNode stub = plannedVisits.get(0);
        assertThat(stub.get("visitDate").asText()).isEqualTo(nextVisitDate.toString());
        assertThat(stub.get("purposeId").asText()).isEqualTo(purposeId);
        // Never a clone of the triggering Visit's other fields.
        assertThat(stub.get("remarks").isNull()).isTrue();
    }

    @Test
    void visitNextVisitDate_onUpdate_createsStubFollowUp() {
        AuthResponse admin = registerOrganization("Lead Event Visit NextVisit Update Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Visit NextVisit Update Co", "Contact VNU", "9666666606");

        Map<String, Object> visitBody = new HashMap<>();
        visitBody.put("leadId", leadId);
        visitBody.put("visitDate", LocalDate.now().toString());
        visitBody.put("visitType", "TELEPHONIC");
        // COMPLETED (not the PLANNED default) so the triggering visit itself doesn't also
        // match the status=PLANNED filter below alongside the auto-created stub.
        visitBody.put("status", "COMPLETED");
        ResponseEntity<String> created = post("/visits", admin.accessToken(), visitBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String visitId = parse(created.getBody()).get("id").asText();

        LocalDate nextVisitDate = LocalDate.now().plusDays(2);
        ResponseEntity<String> updated = put("/visits/" + visitId, admin.accessToken(),
                Map.of("nextVisitDate", nextVisitDate.toString()));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode plannedVisits = getVisitsForLeadByStatus(admin.accessToken(), leadId, "PLANNED");
        assertThat(plannedVisits.size()).isEqualTo(1);
        assertThat(plannedVisits.get(0).get("visitDate").asText()).isEqualTo(nextVisitDate.toString());
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

    private String createLead(String token, Masters masters, String companyName, String contactPerson,
                               String contactNo) {
        Map<String, Object> body = minimalLeadBody(masters, companyName, contactPerson, contactNo);
        ResponseEntity<String> response = post("/leads", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private String firstMasterId(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(response.getBody());
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
    }

    private JsonNode getVisitsForLead(String token, String leadId) {
        ResponseEntity<String> response = get("/visits?leadId=" + leadId, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("content");
    }

    private JsonNode getVisitsForLeadByStatus(String token, String leadId, String status) {
        ResponseEntity<String> response = get("/visits?leadId=" + leadId + "&status=" + status, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("content");
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
                "admin-" + UUID.randomUUID() + "@leadevent.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
