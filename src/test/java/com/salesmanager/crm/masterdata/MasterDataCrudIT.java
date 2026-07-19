package com.salesmanager.crm.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
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
 * Covers ADMIN-only mutation of master data, role-based access (EMPLOYEE gets 403 on
 * mutations but 200 on GET), tenant isolation, and (org, type, code) uniqueness.
 */
class MasterDataCrudIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void admin_canCreateUpdateAndDeactivateAMasterDataEntry() {
        AuthResponse admin = registerOrganization("Master CRUD Org");

        ResponseEntity<String> created = createMaster(admin.accessToken(), MasterType.LEAD_SOURCE,
                "PARTNER_REFERRAL", "Partner Referral", 99);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode createdBody = parse(created.getBody());
        String id = createdBody.get("id").asText();
        assertThat(createdBody.get("code").asText()).isEqualTo("PARTNER_REFERRAL");
        assertThat(createdBody.get("active").asBoolean()).isTrue();

        ResponseEntity<String> updated = putMaster(admin.accessToken(), MasterType.LEAD_SOURCE, id,
                "Partner Referral (Updated)", 5, true);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updatedBody = parse(updated.getBody());
        assertThat(updatedBody.get("label").asText()).isEqualTo("Partner Referral (Updated)");
        assertThat(updatedBody.get("sortOrder").asInt()).isEqualTo(5);
        // code must remain immutable across the update.
        assertThat(updatedBody.get("code").asText()).isEqualTo("PARTNER_REFERRAL");

        ResponseEntity<String> deleted = deleteMaster(admin.accessToken(), MasterType.LEAD_SOURCE, id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Soft-deleted: excluded from the default (active-only) listing...
        JsonNode activeOnly = listMasters(admin.accessToken(), MasterType.LEAD_SOURCE, false);
        assertThat(contains(activeOnly, id)).isFalse();
        // ...but still present, with active=false, when includeInactive=true.
        JsonNode withInactive = listMasters(admin.accessToken(), MasterType.LEAD_SOURCE, true);
        JsonNode found = find(withInactive, id);
        assertThat(found).isNotNull();
        assertThat(found.get("active").asBoolean()).isFalse();
    }

    @Test
    void admin_canReactivateADeactivatedEntryViaPut() {
        AuthResponse admin = registerOrganization("Reactivate Org");
        ResponseEntity<String> created = createMaster(admin.accessToken(), MasterType.LOST_REASON,
                "TEMP_REASON", "Temp Reason", 0);
        String id = parse(created.getBody()).get("id").asText();

        deleteMaster(admin.accessToken(), MasterType.LOST_REASON, id);

        ResponseEntity<String> reactivated = putMaster(admin.accessToken(), MasterType.LOST_REASON, id,
                "Temp Reason", 0, true);
        assertThat(reactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(reactivated.getBody()).get("active").asBoolean()).isTrue();
    }

    @Test
    void employeeRole_getsForbiddenOnMutations_butCanReadList() {
        AuthResponse admin = registerOrganization("Employee Role Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken());

        ResponseEntity<String> createAttempt = createMaster(employee.accessToken(), MasterType.CITY,
                "SOME_CITY", "Some City", 0);
        assertThat(createAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // GET must still work for an EMPLOYEE.
        ResponseEntity<String> listAttempt = restTemplate.exchange(
                baseUrl() + "/masters/" + MasterType.CITY, HttpMethod.GET,
                authEntity(employee.accessToken()), String.class);
        assertThat(listAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Try update/delete on an admin-created entry too.
        ResponseEntity<String> created = createMaster(admin.accessToken(), MasterType.CITY,
                "SECOND_CITY", "Second City", 0);
        String id = parse(created.getBody()).get("id").asText();

        ResponseEntity<String> updateAttempt = putMaster(employee.accessToken(), MasterType.CITY, id,
                "Hacked", 0, true);
        assertThat(updateAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> deleteAttempt = deleteMaster(employee.accessToken(), MasterType.CITY, id);
        assertThat(deleteAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tenantIsolation_orgACannotSeeOrMutateOrgBsMasterData() {
        AuthResponse orgA = registerOrganization("Isolation Org A");
        AuthResponse orgB = registerOrganization("Isolation Org B");

        ResponseEntity<String> orgBEntry = createMaster(orgB.accessToken(), MasterType.PRODUCT,
                "ORG_B_ONLY_PRODUCT", "Org B Only Product", 0);
        String orgBEntryId = parse(orgBEntry.getBody()).get("id").asText();

        JsonNode orgAList = listMasters(orgA.accessToken(), MasterType.PRODUCT, true);
        assertThat(contains(orgAList, orgBEntryId)).isFalse();

        ResponseEntity<String> putAttempt = putMaster(orgA.accessToken(), MasterType.PRODUCT, orgBEntryId,
                "Hijacked", 0, true);
        assertThat(putAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> deleteAttempt = deleteMaster(orgA.accessToken(), MasterType.PRODUCT, orgBEntryId);
        assertThat(deleteAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicateCodeWithinSameOrgAndType_isRejectedWithConflict_butSameCodeInDifferentOrgIsFine() {
        AuthResponse orgA = registerOrganization("Uniqueness Org A");
        AuthResponse orgB = registerOrganization("Uniqueness Org B");

        ResponseEntity<String> firstInA = createMaster(orgA.accessToken(), MasterType.VISIT_PURPOSE,
                "COURTESY_VISIT", "Courtesy Visit", 0);
        assertThat(firstInA.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicateInA = createMaster(orgA.accessToken(), MasterType.VISIT_PURPOSE,
                "COURTESY_VISIT", "Courtesy Visit Again", 1);
        assertThat(duplicateInA.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Same code, different org - must succeed, proving the uniqueness constraint is
        // scoped to (organization, type, code), not global.
        ResponseEntity<String> sameCodeInB = createMaster(orgB.accessToken(), MasterType.VISIT_PURPOSE,
                "COURTESY_VISIT", "Courtesy Visit", 0);
        assertThat(sameCodeInB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void invalidMasterTypeInPath_returnsBadRequestNotServerError() {
        AuthResponse admin = registerOrganization("Bad Type Org");
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/masters/NOT_A_REAL_TYPE", HttpMethod.GET,
                authEntity(admin.accessToken()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- helpers ----

    private ResponseEntity<String> createMaster(String token, MasterType type, String code, String label,
                                                 int sortOrder) {
        Map<String, Object> body = Map.of("code", code, "label", label, "sortOrder", sortOrder);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + "/masters/" + type,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> putMaster(String token, MasterType type, String id, String label,
                                              int sortOrder, boolean active) {
        Map<String, Object> body = Map.of("label", label, "sortOrder", sortOrder, "active", active);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + "/masters/" + type + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Returns String (not Void) even though the happy path is 204 No Content - a
     * forbidden/not-found DELETE still returns a JSON error body, and asking RestTemplate
     * for Void.class leaves that body unread on the pooled HTTP connection, corrupting it
     * for whichever request reuses the connection next (surfaces as a totally unrelated
     * "premature end of chunk" failure in a later test). Always fully consume the body.
     */
    private ResponseEntity<String> deleteMaster(String token, MasterType type, String id) {
        return restTemplate.exchange(baseUrl() + "/masters/" + type + "/" + id, HttpMethod.DELETE,
                authEntity(token), String.class);
    }

    private JsonNode listMasters(String token, MasterType type, boolean includeInactive) {
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/masters/" + type + "?includeInactive=" + includeInactive,
                HttpMethod.GET, authEntity(token), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
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

    private boolean contains(JsonNode array, String id) {
        return find(array, id) != null;
    }

    private JsonNode find(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (node.get("id").asText().equals(id)) {
                return node;
            }
        }
        return null;
    }

    private AuthResponse registerOrganization(String orgName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@masterdata.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken) {
        String email = "employee-" + UUID.randomUUID() + "@masterdata.test";
        String password = "employeepass1";
        Map<String, Object> body = Map.of(
                "fullName", "Test Employee",
                "email", email,
                "password", password,
                "role", Role.EMPLOYEE.name());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminToken);
        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                baseUrl() + "/employees", new HttpEntity<>(body, headers), String.class);
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
