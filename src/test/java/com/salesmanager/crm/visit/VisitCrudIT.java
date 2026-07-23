package com.salesmanager.crm.visit;

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
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Phase 3: Visits CRUD - required-field/past-date validation, ownership-via-parent-lead
 * visibility (EMPLOYEE vs ADMIN), the PLANNED/COMPLETED/MISSED status workflow, pre-fill
 * sync-back onto the parent Lead, and tenant isolation. Follows the same Testcontainers/
 * helper-method style as LeadCrudIT/EmployeeCrudIT/TenantIsolationIT.
 */
class VisitCrudIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private TenantSessionManager tenantSessionManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void update_reschedulingAMissedVisit_flipsStatusBackToPlanned() {
        AuthResponse admin = registerOrganization("Visit Reschedule Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Reschedule Co", "Contact R", "9222222221");
        String visitId = createVisit(admin.accessToken(), minimalVisitBody(leadId, LocalDate.now()));

        markVisitMissed(admin.orgId(), visitId);
        JsonNode beforeReschedule = parse(get("/visits/" + visitId, admin.accessToken()).getBody());
        assertThat(beforeReschedule.get("status").asText()).isEqualTo("MISSED");

        // Rescheduling (a new date/time) puts it back on the calendar - status should flip
        // back to PLANNED, not stay stuck as MISSED forever.
        Map<String, Object> reschedule = new HashMap<>();
        reschedule.put("visitDate", LocalDate.now().plusDays(2).toString());
        reschedule.put("scheduledTime", LocalTime.of(18, 0).toString());
        ResponseEntity<String> updateResponse = put("/visits/" + visitId, admin.accessToken(), reschedule);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = parse(updateResponse.getBody());
        assertThat(updated.get("status").asText()).isEqualTo("PLANNED");
        assertThat(updated.get("visitDate").asText()).isEqualTo(LocalDate.now().plusDays(2).toString());

        // A plain field edit with no date/time change leaves an already-PLANNED visit alone
        // (sanity check that this doesn't somehow force MISSED->PLANNED unconditionally).
        ResponseEntity<String> plainEdit = put("/visits/" + visitId, admin.accessToken(),
                Map.of("remarks", "Just a note"));
        assertThat(parse(plainEdit.getBody()).get("status").asText()).isEqualTo("PLANNED");
    }

    @Test
    void update_editingACompletedVisitsDate_doesNotUnCompleteIt() {
        AuthResponse admin = registerOrganization("Visit Completed Edit Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Completed Edit Co", "Contact C", "9222222222");
        String visitId = createVisit(admin.accessToken(), minimalVisitBody(leadId, LocalDate.now()));

        ResponseEntity<String> completeResponse = patch("/visits/" + visitId + "/status", admin.accessToken(),
                Map.of("status", "COMPLETED"));
        assertThat(completeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Correcting the recorded date on an already-COMPLETED visit must not silently flip
        // it back to PLANNED - COMPLETED is a deliberate terminal state.
        ResponseEntity<String> dateFix = put("/visits/" + visitId, admin.accessToken(),
                Map.of("visitDate", LocalDate.now().toString()));
        assertThat(dateFix.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(dateFix.getBody()).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void create_withOnlyRequiredFields_succeeds() {
        AuthResponse admin = registerOrganization("Visit Minimal Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Acme Corp", "Jane Buyer", "9876543210");

        Map<String, Object> body = minimalVisitBody(leadId, LocalDate.now());
        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = parse(response.getBody());
        assertThat(created.get("leadId").asText()).isEqualTo(leadId);
        assertThat(created.get("visitType").asText()).isEqualTo("FIELD");
        assertThat(created.get("status").asText()).isEqualTo("PLANNED");
        assertThat(created.get("createdBy").asText()).isEqualTo(admin.employeeId().toString());
        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(created.get("createdAt").isNull()).isFalse();
    }

    @Test
    void create_missingLeadId_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Visit Missing Lead Org");
        Map<String, Object> body = minimalVisitBody(null, LocalDate.now());
        body.remove("leadId");

        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_pastVisitDate_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Visit Past Date Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Past Date Co", "Contact P", "9111111112");

        Map<String, Object> body = minimalVisitBody(leadId, LocalDate.now().minusDays(1));
        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_pastNextVisitDate_returnsBadRequest_todayAndFutureSucceed() {
        AuthResponse admin = registerOrganization("Visit Past NextVisit Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Past NextVisit Co", "Contact PN", "9888888885");

        Map<String, Object> pastBody = minimalVisitBody(leadId, LocalDate.now());
        pastBody.put("nextVisitDate", LocalDate.now().minusDays(1).toString());
        ResponseEntity<String> pastResponse = post("/visits", admin.accessToken(), pastBody);
        assertThat(pastResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> todayBody = minimalVisitBody(leadId, LocalDate.now());
        todayBody.put("nextVisitDate", LocalDate.now().toString());
        ResponseEntity<String> todayResponse = post("/visits", admin.accessToken(), todayBody);
        assertThat(todayResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> futureBody = minimalVisitBody(leadId, LocalDate.now());
        futureBody.put("nextVisitDate", LocalDate.now().plusDays(2).toString());
        ResponseEntity<String> futureResponse = post("/visits", admin.accessToken(), futureBody);
        assertThat(futureResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void create_clientSuppliedMissedStatus_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Visit Missed Create Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Missed Create Co", "Contact M", "9111111113");

        Map<String, Object> body = minimalVisitBody(leadId, LocalDate.now());
        body.put("status", "MISSED");
        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateStatus_clientSuppliedMissed_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Visit Missed Status Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Missed Status Co", "Contact N", "9111111114");
        String visitId = createVisit(admin.accessToken(), minimalVisitBody(leadId, LocalDate.now()));

        ResponseEntity<String> response = patch("/visits/" + visitId + "/status", admin.accessToken(),
                Map.of("status", "MISSED"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateStatus_plannedToCompleted_succeeds() {
        AuthResponse admin = registerOrganization("Visit Status Transition Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Status Transition Co", "Contact O", "9111111115");
        String visitId = createVisit(admin.accessToken(), minimalVisitBody(leadId, LocalDate.now()));

        ResponseEntity<String> response = patch("/visits/" + visitId + "/status", admin.accessToken(),
                Map.of("status", "COMPLETED"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response.getBody()).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void ownership_employeeCannotCreateViewOrUpdateVisitUnderColleaguesLead_adminCan() {
        AuthResponse admin = registerOrganization("Visit Ownership Org");
        AuthResponse employeeA = createAndLoginEmployee(admin.accessToken(), "visitEmpA");
        AuthResponse employeeB = createAndLoginEmployee(admin.accessToken(), "visitEmpB");
        Masters masters = loadMasters(admin.accessToken());

        String leadId = createLead(employeeA.accessToken(), masters, "Employee A Visit Co", "Contact A", "9222222221");

        // Employee B cannot create a visit under Employee A's lead.
        ResponseEntity<String> employeeBCreate = post("/visits", employeeB.accessToken(),
                minimalVisitBody(leadId, LocalDate.now()));
        assertThat(employeeBCreate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Employee A can create/view/update a visit under their own lead.
        String visitId = createVisit(employeeA.accessToken(), minimalVisitBody(leadId, LocalDate.now()));
        assertThat(get("/visits/" + visitId, employeeA.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> employeeAUpdate = put("/visits/" + visitId, employeeA.accessToken(),
                Map.of("remarks", "Updated by owner"));
        assertThat(employeeAUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Employee B cannot view or update that visit.
        assertThat(get("/visits/" + visitId, employeeB.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> employeeBUpdate = put("/visits/" + visitId, employeeB.accessToken(),
                Map.of("remarks", "Hijacked"));
        assertThat(employeeBUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ADMIN can view/update it too.
        assertThat(get("/visits/" + visitId, admin.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> adminUpdate = put("/visits/" + visitId, admin.accessToken(),
                Map.of("remarks", "Updated by admin"));
        assertThat(adminUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ADMIN can also create a visit under employee A's lead.
        ResponseEntity<String> adminCreate = post("/visits", admin.accessToken(),
                minimalVisitBody(leadId, LocalDate.now()));
        assertThat(adminCreate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void create_syncsBackContactFieldsToParentLead() {
        AuthResponse admin = registerOrganization("Visit Sync Back Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Sync Back Co", "Old Contact", "9333333331");
        String hotInterestLevelId = interestLevelIdByCode(admin.accessToken(), "HOT");

        Map<String, Object> body = minimalVisitBody(leadId, LocalDate.now());
        body.put("contactNo", "9444444441");
        body.put("interestLevelId", hotInterestLevelId);
        body.put("budgetRange", "5-10 Lakh");

        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> leadFetch = get("/leads/" + leadId, admin.accessToken());
        assertThat(leadFetch.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode lead = parse(leadFetch.getBody());
        assertThat(lead.get("contactNo").asText()).isEqualTo("9444444441");
        assertThat(lead.get("interestLevelId").asText()).isEqualTo(hotInterestLevelId);
        assertThat(lead.get("budgetRange").asText()).isEqualTo("5-10 Lakh");
        // Untouched field (contactPerson wasn't in the visit body) must remain as it was.
        assertThat(lead.get("contactPerson").asText()).isEqualTo("Old Contact");
    }

    @Test
    void tenantIsolation_orgACannotSeeFetchOrUpdateOrgBsVisits() {
        AuthResponse orgA = registerOrganization("Visit Isolation Org A");
        AuthResponse orgB = registerOrganization("Visit Isolation Org B");
        Masters orgBMasters = loadMasters(orgB.accessToken());
        String orgBLeadId = createLead(orgB.accessToken(), orgBMasters, "Org B Visit Co", "Org B Contact", "9555555551");
        String orgBVisitId = createVisit(orgB.accessToken(), minimalVisitBody(orgBLeadId, LocalDate.now()));

        assertThat(get("/visits/" + orgBVisitId, orgA.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> updateAttempt = put("/visits/" + orgBVisitId, orgA.accessToken(),
                Map.of("remarks", "Cross tenant hijack"));
        assertThat(updateAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode orgAList = getVisitsList(orgA.accessToken());
        assertThat(orgAList.get("content").size()).isEqualTo(0);
    }

    @Test
    void create_purposeIdAndPurposeOtherBothSet_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Visit Creatable Conflict Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Purpose Conflict Co", "Contact PC", "9888888881");
        String purposeId = firstMasterId(admin.accessToken(), MasterType.VISIT_PURPOSE);

        Map<String, Object> body = minimalVisitBody(leadId, LocalDate.now());
        body.put("purposeId", purposeId);
        body.put("purposeOther", "Some new purpose");

        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_purposeOtherOnly_succeeds_andResponseReflectsFreeTextFallback() {
        AuthResponse admin = registerOrganization("Visit Creatable Fallback Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Purpose Fallback Co", "Contact PF", "9888888882");

        Map<String, Object> body = minimalVisitBody(leadId, LocalDate.now());
        body.put("purposeOther", "A brand new visit purpose");

        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = parse(response.getBody());
        assertThat(created.get("purposeId").isNull()).isTrue();
        assertThat(created.get("purposeOther").asText()).isEqualTo("A brand new visit purpose");
    }

    @Test
    void create_cityIdBelongingToDifferentState_returnsBadRequestWithFieldError() {
        AuthResponse admin = registerOrganization("Visit State City Mismatch Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Visit Mismatch Co", "Contact VM", "9888888883");
        CityAndState mismatch = findCityWithDifferentState(admin.accessToken());

        Map<String, Object> body = minimalVisitBody(leadId, LocalDate.now());
        body.put("cityId", mismatch.cityId());
        body.put("stateId", mismatch.wrongStateId());

        ResponseEntity<String> response = post("/visits", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode responseBody = parse(response.getBody());
        assertThat(responseBody.get("fieldErrors").get(0).get("field").asText()).isEqualTo("cityId");
    }

    @Test
    void update_cityIdBelongingToDifferentState_returnsBadRequestWithFieldError() {
        AuthResponse admin = registerOrganization("Visit State City Mismatch Update Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Visit Mismatch Update Co", "Contact VU",
                "9888888884");
        String visitId = createVisit(admin.accessToken(), minimalVisitBody(leadId, LocalDate.now()));
        CityAndState mismatch = findCityWithDifferentState(admin.accessToken());

        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("cityId", mismatch.cityId());
        updateBody.put("stateId", mismatch.wrongStateId());

        ResponseEntity<String> response = put("/visits/" + visitId, admin.accessToken(), updateBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameDay_returnsExistingVisitForLeadAndDate_andEmptyListWhenNone() {
        AuthResponse admin = registerOrganization("Visit SameDay Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "SameDay Co", "Contact SD", "9888888886");
        // A future date (not today) so the manually-created Visit below is the ONLY one on
        // the books for that date - createLead's Lead creation itself auto-schedules a
        // separate COMPLETED Visit dated TODAY (logAsVisitToday defaults to true), which would
        // otherwise collide with a same-day check dated today.
        LocalDate visitDate = LocalDate.now().plusDays(2);
        String visitId = createVisit(admin.accessToken(), minimalVisitBody(leadId, visitDate));

        JsonNode matches = getSameDay(admin.accessToken(), leadId, visitDate);
        assertThat(matches.size()).isEqualTo(1);
        JsonNode match = matches.get(0);
        assertThat(match.get("id").asText()).isEqualTo(visitId);
        assertThat(match.get("visitDate").asText()).isEqualTo(visitDate.toString());
        assertThat(match.get("status").asText()).isEqualTo("PLANNED");
        assertThat(match.get("visitType").asText()).isEqualTo("FIELD");

        JsonNode noMatches = getSameDay(admin.accessToken(), leadId, visitDate.plusDays(1));
        assertThat(noMatches.size()).isEqualTo(0);
    }

    @Test
    void sameDay_crossTenantLeadId_returnsNotFound() {
        AuthResponse orgA = registerOrganization("Visit SameDay Org A");
        AuthResponse orgB = registerOrganization("Visit SameDay Org B");
        Masters orgBMasters = loadMasters(orgB.accessToken());
        String orgBLeadId = createLead(orgB.accessToken(), orgBMasters, "SameDay Org B Co", "Contact SDB",
                "9888888887");

        ResponseEntity<String> response = get(
                "/visits/same-day?leadId=" + orgBLeadId + "&visitDate=" + LocalDate.now(), orgA.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private record Masters(String cityId, String leadSourceId, String industryId) {
    }

    private record CityAndState(String cityId, String wrongStateId) {
    }

    /**
     * Finds a seeded CITY row and a seeded STATE row that is NOT that city's actual parent
     * state - same helper as LeadCrudIT's, used to prove the cityId/stateId cross-check
     * (MasterDataService#validateReference's 4-arg overload) rejects a mismatched pair.
     */
    private CityAndState findCityWithDifferentState(String token) {
        JsonNode cities = allMasters(token, MasterType.CITY);
        JsonNode states = allMasters(token, MasterType.STATE);
        assertThat(cities.size()).isGreaterThan(0);
        assertThat(states.size()).isGreaterThan(1);

        String cityId = cities.get(0).get("id").asText();
        String correctStateId = cities.get(0).get("parentId").asText();
        for (JsonNode state : states) {
            String candidateId = state.get("id").asText();
            if (!candidateId.equals(correctStateId)) {
                return new CityAndState(cityId, candidateId);
            }
        }
        throw new IllegalStateException("Could not find a STATE row different from the city's actual parent");
    }

    private JsonNode allMasters(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private Masters loadMasters(String adminToken) {
        return new Masters(
                firstMasterId(adminToken, MasterType.CITY),
                firstMasterId(adminToken, MasterType.LEAD_SOURCE),
                firstMasterId(adminToken, MasterType.INDUSTRY));
    }

    private Map<String, Object> minimalVisitBody(String leadId, LocalDate visitDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("leadId", leadId);
        body.put("visitDate", visitDate.toString());
        body.put("visitType", "FIELD");
        return body;
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
        ResponseEntity<String> response = post("/leads", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private String createVisit(String token, Map<String, Object> body) {
        ResponseEntity<String> response = post("/visits", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    /**
     * Directly flips a Visit to MISSED via VisitRepository, bypassing VisitService's
     * client-supplied-MISSED rejection - same approach as ReportingIT#markVisitMissed, needed
     * here since the ordinary API can never produce a MISSED visit to test rescheduling against.
     */
    private void markVisitMissed(UUID orgId, String visitId) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            tenantSessionManager.activateTenant(orgId);
            try {
                Visit visit = visitRepository.findById(UUID.fromString(visitId)).orElseThrow();
                visit.setStatus(VisitStatus.MISSED);
                visitRepository.saveAndFlush(visit);
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private String firstMasterId(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(response.getBody());
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
    }

    private String interestLevelIdByCode(String token, String code) {
        ResponseEntity<String> response = get("/masters/" + MasterType.INTEREST_LEVEL, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(response.getBody());
        for (JsonNode entry : entries) {
            if (entry.get("code").asText().equalsIgnoreCase(code)) {
                return entry.get("id").asText();
            }
        }
        throw new IllegalStateException("No INTEREST_LEVEL master row with code " + code);
    }

    private JsonNode getVisitsList(String token) {
        ResponseEntity<String> response = get("/visits", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private JsonNode getSameDay(String token, String leadId, LocalDate visitDate) {
        ResponseEntity<String> response = get("/visits/same-day?leadId=" + leadId + "&visitDate=" + visitDate, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
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

    private AuthResponse registerOrganization(String orgName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@visitcrud.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@visitcrud.test";
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
