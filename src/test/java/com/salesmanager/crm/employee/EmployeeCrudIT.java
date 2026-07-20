package com.salesmanager.crm.employee;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.masterdata.MasterType;
import java.util.List;
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
 * Full Employee CRUD: lifecycle (create -> fetch -> update -> deactivate), master-data
 * reference validation on create, and ADMIN-only enforcement on the mutating endpoints.
 */
class EmployeeCrudIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullLifecycle_createFetchUpdateDeactivate_roundTripsMasterDataReferences() {
        AuthResponse admin = registerOrganization("Employee Lifecycle Org");
        String designationId = firstMasterId(admin.accessToken(), MasterType.DESIGNATION);
        String cityId = firstMasterId(admin.accessToken(), MasterType.CITY);
        List<String> productIds = twoMasterIds(admin.accessToken(), MasterType.PRODUCT);

        Map<String, Object> createBody = Map.of(
                "fullName", "New Hire",
                "email", "newhire-" + UUID.randomUUID() + "@lifecycle.test",
                "phone", "9812345670",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name(),
                "designationId", designationId,
                "cityId", cityId,
                "assignedProductIds", productIds);

        ResponseEntity<String> created = post("/employees", admin.accessToken(), createBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode createdBody = parse(created.getBody());
        String employeeId = createdBody.get("id").asText();
        assertThat(createdBody.get("designationId").asText()).isEqualTo(designationId);
        assertThat(createdBody.get("cityId").asText()).isEqualTo(cityId);
        assertThat(toStringList(createdBody.get("assignedProductIds"))).containsExactlyInAnyOrderElementsOf(productIds);

        ResponseEntity<String> fetched = get("/employees/" + employeeId, admin.accessToken());
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode fetchedBody = parse(fetched.getBody());
        assertThat(fetchedBody.get("designationId").asText()).isEqualTo(designationId);
        assertThat(fetchedBody.get("cityId").asText()).isEqualTo(cityId);
        assertThat(toStringList(fetchedBody.get("assignedProductIds"))).containsExactlyInAnyOrderElementsOf(productIds);
        assertThat(fetchedBody.get("active").asBoolean()).isTrue();

        Map<String, Object> updateBody = Map.of("fullName", "New Hire Updated", "phone", "9812345671");
        ResponseEntity<String> updated = put("/employees/" + employeeId, admin.accessToken(), updateBody);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updatedBody = parse(updated.getBody());
        assertThat(updatedBody.get("fullName").asText()).isEqualTo("New Hire Updated");
        assertThat(updatedBody.get("phone").asText()).isEqualTo("9812345671");
        // Untouched fields must remain as they were.
        assertThat(updatedBody.get("designationId").asText()).isEqualTo(designationId);

        ResponseEntity<String> deactivated = patch("/employees/" + employeeId + "/deactivate", admin.accessToken());
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(deactivated.getBody()).get("active").asBoolean()).isFalse();

        ResponseEntity<String> refetched = get("/employees/" + employeeId, admin.accessToken());
        assertThat(parse(refetched.getBody()).get("active").asBoolean()).isFalse();
    }

    @Test
    void create_withNonExistentDesignationId_isRejectedWithBadRequest() {
        AuthResponse admin = registerOrganization("Bad Reference Org");

        Map<String, Object> createBody = Map.of(
                "fullName", "Bad Ref Employee",
                "email", "badref-" + UUID.randomUUID() + "@lifecycle.test",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name(),
                "designationId", UUID.randomUUID().toString());

        ResponseEntity<String> response = post("/employees", admin.accessToken(), createBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_withBadPhoneFormat_isRejectedWithBadRequest_andValid10DigitPhoneSucceeds() {
        AuthResponse admin = registerOrganization("Employee Phone Format Org");

        Map<String, Object> badPhoneBody = Map.of(
                "fullName", "Bad Phone Employee",
                "email", "badphone-" + UUID.randomUUID() + "@lifecycle.test",
                "phone", "12345",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name());
        ResponseEntity<String> badPhoneResponse = post("/employees", admin.accessToken(), badPhoneBody);
        assertThat(badPhoneResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> validPhoneBody = Map.of(
                "fullName", "Good Phone Employee",
                "email", "goodphone-" + UUID.randomUUID() + "@lifecycle.test",
                "phone", "9812345699",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name());
        ResponseEntity<String> validPhoneResponse = post("/employees", admin.accessToken(), validPhoneBody);
        assertThat(validPhoneResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(parse(validPhoneResponse.getBody()).get("phone").asText()).isEqualTo("9812345699");
    }

    @Test
    void create_withCityIdPassedAsDesignationId_isRejectedWithBadRequest() {
        AuthResponse admin = registerOrganization("Wrong Type Reference Org");
        String cityId = firstMasterId(admin.accessToken(), MasterType.CITY);

        Map<String, Object> createBody = Map.of(
                "fullName", "Wrong Type Employee",
                "email", "wrongtype-" + UUID.randomUUID() + "@lifecycle.test",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name(),
                "designationId", cityId);

        ResponseEntity<String> response = post("/employees", admin.accessToken(), createBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("designationId");
    }

    @Test
    void create_withNonExistentStateId_isRejectedWithBadRequest() {
        AuthResponse admin = registerOrganization("Bad State Reference Org");

        Map<String, Object> createBody = Map.of(
                "fullName", "Bad State Employee",
                "email", "badstate-" + UUID.randomUUID() + "@lifecycle.test",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name(),
                "stateId", UUID.randomUUID().toString());

        ResponseEntity<String> response = post("/employees", admin.accessToken(), createBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_withCityIdPassedAsStateId_isRejectedWithBadRequest() {
        AuthResponse admin = registerOrganization("Wrong Type State Reference Org");
        String cityId = firstMasterId(admin.accessToken(), MasterType.CITY);

        Map<String, Object> createBody = Map.of(
                "fullName", "Wrong Type State Employee",
                "email", "wrongtypestate-" + UUID.randomUUID() + "@lifecycle.test",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name(),
                "stateId", cityId);

        ResponseEntity<String> response = post("/employees", admin.accessToken(), createBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("stateId");
    }

    @Test
    void create_withCityIdBelongingToADifferentState_isRejectedWithBadRequest() {
        AuthResponse admin = registerOrganization("Employee State City Mismatch Org");
        CityAndState mismatch = findCityWithDifferentState(admin.accessToken());

        Map<String, Object> createBody = Map.of(
                "fullName", "Mismatched Employee",
                "email", "mismatched-" + UUID.randomUUID() + "@lifecycle.test",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name(),
                "cityId", mismatch.cityId(),
                "stateId", mismatch.wrongStateId());

        ResponseEntity<String> response = post("/employees", admin.accessToken(), createBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_withCityIdBelongingToADifferentState_isRejectedWithBadRequest() {
        AuthResponse admin = registerOrganization("Employee State City Mismatch Update Org");
        String employeeId = admin.employeeId().toString();
        CityAndState mismatch = findCityWithDifferentState(admin.accessToken());

        Map<String, Object> updateBody = Map.of(
                "cityId", mismatch.cityId(),
                "stateId", mismatch.wrongStateId());

        ResponseEntity<String> response = put("/employees/" + employeeId, admin.accessToken(), updateBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_withMatchingCityAndStateId_succeeds() {
        AuthResponse admin = registerOrganization("Employee State City Match Org");
        JsonNode cities = getMasters(admin.accessToken(), MasterType.CITY);
        String cityId = cities.get(0).get("id").asText();
        String stateId = cities.get(0).get("parentId").asText();

        Map<String, Object> createBody = Map.of(
                "fullName", "Matching Employee",
                "email", "matching-" + UUID.randomUUID() + "@lifecycle.test",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name(),
                "cityId", cityId,
                "stateId", stateId);

        ResponseEntity<String> response = post("/employees", admin.accessToken(), createBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("cityId").asText()).isEqualTo(cityId);
        assertThat(body.get("stateId").asText()).isEqualTo(stateId);
    }

    @Test
    void employeeRole_getsForbiddenOnCreateUpdateDeactivate() {
        AuthResponse admin = registerOrganization("Employee Forbidden Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken());

        Map<String, Object> createBody = Map.of(
                "fullName", "Someone Else",
                "email", "someoneelse-" + UUID.randomUUID() + "@lifecycle.test",
                "password", "supersecret1",
                "role", Role.EMPLOYEE.name());
        ResponseEntity<String> createAttempt = post("/employees", employee.accessToken(), createBody);
        assertThat(createAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> updateBody = Map.of("fullName", "Hacked Name");
        ResponseEntity<String> updateAttempt = put("/employees/" + employee.employeeId(), employee.accessToken(),
                updateBody);
        assertThat(updateAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> deactivateAttempt = patch("/employees/" + employee.employeeId() + "/deactivate",
                employee.accessToken());
        assertThat(deactivateAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- helpers ----

    private record CityAndState(String cityId, String wrongStateId) {
    }

    /**
     * Finds a seeded CITY row and a seeded STATE row that is NOT that city's actual parent
     * state - used to prove the cityId/stateId cross-check (MasterDataService#validateReference's
     * 4-arg overload) rejects a mismatched pair, same helper as LeadCrudIT's.
     */
    private CityAndState findCityWithDifferentState(String token) {
        JsonNode cities = getMasters(token, MasterType.CITY);
        JsonNode states = getMasters(token, MasterType.STATE);
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

    private String firstMasterId(String token, MasterType type) {
        JsonNode entries = getMasters(token, type);
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
    }

    private List<String> twoMasterIds(String token, MasterType type) {
        JsonNode entries = getMasters(token, type);
        assertThat(entries.size()).isGreaterThanOrEqualTo(2);
        return List.of(entries.get(0).get("id").asText(), entries.get(1).get("id").asText());
    }

    private JsonNode getMasters(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private List<String> toStringList(JsonNode arrayNode) {
        return java.util.stream.StreamSupport.stream(arrayNode.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
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
                "admin-" + UUID.randomUUID() + "@employeecrud.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken) {
        String email = "employee-" + UUID.randomUUID() + "@employeecrud.test";
        String password = "employeepass1";
        Map<String, Object> body = Map.of(
                "fullName", "Test Employee",
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
