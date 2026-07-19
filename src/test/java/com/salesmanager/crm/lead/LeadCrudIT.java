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
 * Phase 2: Leads CRUD - required-field validation, duplicate-lead check, owner-scoped
 * visibility (EMPLOYEE vs ADMIN), the Lost-lead workflow, and tenant isolation. Follows the
 * same Testcontainers/helper-method style as EmployeeCrudIT/MasterDataCrudIT/TenantIsolationIT.
 */
class LeadCrudIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void create_withOnlyTheSixRequiredFields_succeeds() {
        AuthResponse admin = registerOrganization("Lead Minimal Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Acme Corp", "Jane Buyer", "9876543210");
        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = parse(response.getBody());
        assertThat(created.get("companyName").asText()).isEqualTo("Acme Corp");
        assertThat(created.get("contactPerson").asText()).isEqualTo("Jane Buyer");
        assertThat(created.get("contactNo").asText()).isEqualTo("9876543210");
        assertThat(created.get("status").asText()).isEqualTo("NEW");
        assertThat(created.get("ownerId").asText()).isEqualTo(admin.employeeId().toString());
        assertThat(created.get("createdBy").asText()).isEqualTo(admin.employeeId().toString());
        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(created.get("createdAt").isNull()).isFalse();
    }

    @Test
    void create_missingRequiredField_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Lead Missing Field Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Acme Corp", "Jane Buyer", "9876543210");
        body.remove("contactNo");

        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_badContactNoFormat_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Lead Bad Contact Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Acme Corp", "Jane Buyer", "12345");
        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_badEmailFormat_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Lead Bad Email Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Acme Corp", "Jane Buyer", "9876543210");
        body.put("email", "not-an-email");
        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_industryIdPointingAtWrongMasterType_returnsBadRequestWithFieldError() {
        AuthResponse admin = registerOrganization("Lead Wrong Type Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Acme Corp", "Jane Buyer", "9876543210");
        body.put("industryId", masters.cityId); // a CITY id where an INDUSTRY id is expected

        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode responseBody = parse(response.getBody());
        assertThat(responseBody.get("fieldErrors").get(0).get("field").asText()).isEqualTo("industryId");
    }

    @Test
    void create_nonExistentIndustryId_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Lead Nonexistent Ref Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Acme Corp", "Jane Buyer", "9876543210");
        body.put("industryId", UUID.randomUUID().toString());

        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateCheck_matchesOnContactNoOrCompanyName_andIsEmptyForGenuinelyNew() {
        AuthResponse admin = registerOrganization("Lead Duplicate Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Duplicate Target Co", "Original Contact", "9111111111");
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode byContactNo = getDuplicates(admin.accessToken(), "9111111111", null);
        assertThat(byContactNo.size()).isEqualTo(1);
        assertThat(byContactNo.get(0).get("contactNo").asText()).isEqualTo("9111111111");

        JsonNode byCompanyName = getDuplicates(admin.accessToken(), null, "Duplicate Target Co");
        assertThat(byCompanyName.size()).isEqualTo(1);
        assertThat(byCompanyName.get(0).get("companyName").asText()).isEqualTo("Duplicate Target Co");

        JsonNode noMatch = getDuplicates(admin.accessToken(), "9222222222", "Totally Different Co");
        assertThat(noMatch.size()).isEqualTo(0);
    }

    @Test
    void ownershipVisibility_employeeSeesOnlyOwnLeads_adminSeesAll() {
        AuthResponse admin = registerOrganization("Lead Ownership Org");
        AuthResponse employeeA = createAndLoginEmployee(admin.accessToken(), "empA");
        AuthResponse employeeB = createAndLoginEmployee(admin.accessToken(), "empB");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Employee A's Lead Co", "Contact A", "9333333333");
        ResponseEntity<String> created = post("/leads", employeeA.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String leadId = parse(created.getBody()).get("id").asText();

        // Employee A can list/fetch/update their own lead.
        JsonNode employeeAList = getLeadsList(employeeA.accessToken());
        assertThat(employeeAList.get("content").size()).isEqualTo(1);
        assertThat(get("/leads/" + leadId, employeeA.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> employeeAUpdate = put("/leads/" + leadId, employeeA.accessToken(),
                Map.of("remarks", "Updated by owner"));
        assertThat(employeeAUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Employee B (a different EMPLOYEE, same org) must not see it at all.
        JsonNode employeeBList = getLeadsList(employeeB.accessToken());
        assertThat(employeeBList.get("content").size()).isEqualTo(0);
        assertThat(get("/leads/" + leadId, employeeB.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> employeeBUpdate = put("/leads/" + leadId, employeeB.accessToken(),
                Map.of("remarks", "Hijacked"));
        assertThat(employeeBUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ADMIN can list/fetch/update it too.
        JsonNode adminList = getLeadsList(admin.accessToken());
        assertThat(adminList.get("content").size()).isEqualTo(1);
        assertThat(get("/leads/" + leadId, admin.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> adminUpdate = put("/leads/" + leadId, admin.accessToken(),
                Map.of("remarks", "Updated by admin"));
        assertThat(adminUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        // An EMPLOYEE cannot use the ownerId query param to see someone else's leads either.
        ResponseEntity<String> manipulated = restTemplate.exchange(
                baseUrl() + "/leads?ownerId=" + employeeA.employeeId(), HttpMethod.GET,
                authEntity(employeeB.accessToken()), String.class);
        assertThat(manipulated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(manipulated.getBody()).get("content").size()).isEqualTo(0);
    }

    @Test
    void lostWorkflow_requiresLostReasonId_andAutoSetsInterestLevelToCold() {
        AuthResponse admin = registerOrganization("Lead Lost Workflow Org");
        Masters masters = loadMasters(admin.accessToken());
        String lostReasonId = firstMasterId(admin.accessToken(), MasterType.LOST_REASON);
        String coldInterestLevelId = interestLevelIdByCode(admin.accessToken(), "COLD");

        Map<String, Object> body = minimalLeadBody(masters, "Lost Workflow Co", "Contact Lost", "9444444444");
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        String leadId = parse(created.getBody()).get("id").asText();

        // Missing lostReasonId must be rejected.
        ResponseEntity<String> missingReason = patch("/leads/" + leadId + "/status", admin.accessToken(),
                Map.of("status", "LOST"));
        assertThat(missingReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // With a valid lostReasonId, status flips to LOST and interestLevelId is auto-set to COLD.
        ResponseEntity<String> lostResponse = patch("/leads/" + leadId + "/status", admin.accessToken(),
                Map.of("status", "LOST", "lostReasonId", lostReasonId));
        assertThat(lostResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode lostBody = parse(lostResponse.getBody());
        assertThat(lostBody.get("status").asText()).isEqualTo("LOST");
        assertThat(lostBody.get("lostReasonId").asText()).isEqualTo(lostReasonId);
        assertThat(lostBody.get("interestLevelId").asText()).isEqualTo(coldInterestLevelId);
    }

    @Test
    void nonLostStatusUpdate_doesNotRequireOrTouchLostReasonOrInterestLevel() {
        AuthResponse admin = registerOrganization("Lead Status Update Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Contacted Co", "Contact Person", "9555555555");
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        String leadId = parse(created.getBody()).get("id").asText();

        ResponseEntity<String> response = patch("/leads/" + leadId + "/status", admin.accessToken(),
                Map.of("status", "CONTACTED"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode responseBody = parse(response.getBody());
        assertThat(responseBody.get("status").asText()).isEqualTo("CONTACTED");
        assertThat(responseBody.get("lostReasonId").isNull()).isTrue();
    }

    @Test
    void tenantIsolation_orgACannotSeeFetchOrUpdateOrgBsLeads() {
        AuthResponse orgA = registerOrganization("Lead Isolation Org A");
        AuthResponse orgB = registerOrganization("Lead Isolation Org B");
        Masters orgBMasters = loadMasters(orgB.accessToken());

        Map<String, Object> body = minimalLeadBody(orgBMasters, "Org B Only Co", "Org B Contact", "9666666666");
        ResponseEntity<String> created = post("/leads", orgB.accessToken(), body);
        String orgBLeadId = parse(created.getBody()).get("id").asText();

        JsonNode orgAList = getLeadsList(orgA.accessToken());
        assertThat(orgAList.get("content").size()).isEqualTo(0);

        assertThat(get("/leads/" + orgBLeadId, orgA.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> updateAttempt = put("/leads/" + orgBLeadId, orgA.accessToken(),
                Map.of("remarks", "Cross tenant hijack"));
        assertThat(updateAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_industryIdAndIndustryOtherBothSet_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Lead Creatable Conflict Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "Conflict Co", "Conflict Contact", "9777777771");
        body.put("industryOther", "Some New Industry");

        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_leadSourceOtherOnly_succeeds_andResponseReflectsFreeTextFallback() {
        AuthResponse admin = registerOrganization("Lead Creatable Fallback Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = new HashMap<>();
        body.put("companyName", "Fallback Co");
        body.put("contactPerson", "Fallback Contact");
        body.put("contactNo", "9777777772");
        body.put("cityId", masters.cityId);
        body.put("industryId", masters.industryId);
        // Deliberately no leadSourceId - only its free-text fallback.
        body.put("leadSourceOther", "A brand new referral channel");

        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = parse(response.getBody());
        assertThat(created.get("leadSourceId").isNull()).isTrue();
        assertThat(created.get("leadSourceOther").asText()).isEqualTo("A brand new referral channel");
    }

    @Test
    void create_missingBothLeadSourceIdAndOther_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Lead Creatable Missing Org");
        Masters masters = loadMasters(admin.accessToken());

        Map<String, Object> body = new HashMap<>();
        body.put("companyName", "Missing Source Co");
        body.put("contactPerson", "Missing Source Contact");
        body.put("contactNo", "9777777773");
        body.put("cityId", masters.cityId);
        body.put("industryId", masters.industryId);
        // Neither leadSourceId nor leadSourceOther supplied.

        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_cityIdBelongingToDifferentState_returnsBadRequestWithFieldError() {
        AuthResponse admin = registerOrganization("Lead State City Mismatch Org");
        Masters masters = loadMasters(admin.accessToken());
        CityAndState mismatch = findCityWithDifferentState(admin.accessToken());

        Map<String, Object> body = minimalLeadBody(masters, "State Mismatch Co", "State Mismatch Contact",
                "9777777774");
        body.put("cityId", mismatch.cityId());
        body.put("stateId", mismatch.wrongStateId());

        ResponseEntity<String> response = post("/leads", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode responseBody = parse(response.getBody());
        assertThat(responseBody.get("fieldErrors").get(0).get("field").asText()).isEqualTo("cityId");
    }

    @Test
    void update_cityIdBelongingToDifferentState_returnsBadRequestWithFieldError() {
        AuthResponse admin = registerOrganization("Lead State City Mismatch Update Org");
        Masters masters = loadMasters(admin.accessToken());
        Map<String, Object> body = minimalLeadBody(masters, "State Mismatch Update Co", "Contact", "9777777775");
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        String leadId = parse(created.getBody()).get("id").asText();

        CityAndState mismatch = findCityWithDifferentState(admin.accessToken());
        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("cityId", mismatch.cityId());
        updateBody.put("stateId", mismatch.wrongStateId());

        ResponseEntity<String> response = put("/leads/" + leadId, admin.accessToken(), updateBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lostWorkflow_lostReasonOther_satisfiesRequirement_whenLostReasonIdOmitted() {
        AuthResponse admin = registerOrganization("Lead Lost Other Org");
        Masters masters = loadMasters(admin.accessToken());
        String coldInterestLevelId = interestLevelIdByCode(admin.accessToken(), "COLD");

        Map<String, Object> body = minimalLeadBody(masters, "Lost Other Co", "Contact Lost Other", "9777777776");
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        String leadId = parse(created.getBody()).get("id").asText();

        ResponseEntity<String> response = patch("/leads/" + leadId + "/status", admin.accessToken(),
                Map.of("status", "LOST", "lostReasonOther", "A brand new reason not yet in master data"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode responseBody = parse(response.getBody());
        assertThat(responseBody.get("status").asText()).isEqualTo("LOST");
        assertThat(responseBody.get("lostReasonId").isNull()).isTrue();
        assertThat(responseBody.get("lostReasonOther").asText()).isEqualTo("A brand new reason not yet in master data");
        assertThat(responseBody.get("interestLevelId").asText()).isEqualTo(coldInterestLevelId);
    }

    @Test
    void lostWorkflow_bothLostReasonIdAndOtherSet_returnsBadRequest() {
        AuthResponse admin = registerOrganization("Lead Lost Both Org");
        Masters masters = loadMasters(admin.accessToken());
        String lostReasonId = firstMasterId(admin.accessToken(), MasterType.LOST_REASON);

        Map<String, Object> body = minimalLeadBody(masters, "Lost Both Co", "Contact Lost Both", "9777777777");
        ResponseEntity<String> created = post("/leads", admin.accessToken(), body);
        String leadId = parse(created.getBody()).get("id").asText();

        ResponseEntity<String> response = patch("/leads/" + leadId + "/status", admin.accessToken(),
                Map.of("status", "LOST", "lostReasonId", lostReasonId, "lostReasonOther", "Conflicting text"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- helpers ----

    private record Masters(String cityId, String leadSourceId, String industryId) {
    }

    private record CityAndState(String cityId, String wrongStateId) {
    }

    /**
     * Finds a seeded CITY row and a seeded STATE row that is NOT that city's actual parent
     * state - used to prove the cityId/stateId cross-check (MasterDataService#validateReference's
     * 4-arg overload) rejects a mismatched pair.
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

    private JsonNode getLeadsList(String token) {
        ResponseEntity<String> response = get("/leads", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private JsonNode getDuplicates(String token, String contactNo, String companyName) {
        // Pass the raw (unencoded) value and let RestTemplate's UriComponentsBuilder do the
        // percent-encoding exactly once - manually pre-encoding (e.g. replacing spaces with
        // "%20") here would get double-encoded ("%2520"), corrupting the query param value.
        StringBuilder query = new StringBuilder("/leads/duplicates?");
        if (contactNo != null) {
            query.append("contactNo=").append(contactNo).append("&");
        }
        if (companyName != null) {
            query.append("companyName=").append(companyName);
        }
        ResponseEntity<String> response = get(query.toString(), token);
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
                "admin-" + UUID.randomUUID() + "@leadcrud.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@leadcrud.test";
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
