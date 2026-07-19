package com.salesmanager.crm.theme;

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
 * Phase 6: org-level theme branding (Organization#themeSettings, ADMIN-configurable) plus a
 * personal per-employee override (Employee#themePreference, any authenticated user). Covers
 * the documented org defaults, partial-merge semantics on both PUT endpoints, role
 * enforcement, field validation, and tenant isolation.
 */
class ThemeSettingsIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void freshOrg_getOrganizationTheme_returnsDocumentedDefaults_beforeAnyPut() {
        AuthResponse admin = registerOrganization("Fresh Theme Org");

        ResponseEntity<String> response = get("/organizations/me/theme", admin.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("primaryColor").asText()).isEqualTo("#1565c0");
        assertThat(body.get("mode").asText()).isEqualTo("LIGHT");
        assertThat(body.get("density").asText()).isEqualTo("COMFORTABLE");
    }

    @Test
    void admin_partialUpdates_mergeInsteadOfOverwriting() {
        AuthResponse admin = registerOrganization("Partial Merge Org");

        // First PUT: only mode.
        ResponseEntity<String> firstPut = put("/organizations/me/theme", admin.accessToken(),
                Map.of("mode", "DARK"));
        assertThat(firstPut.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode afterFirst = parse(firstPut.getBody());
        assertThat(afterFirst.get("mode").asText()).isEqualTo("DARK");
        assertThat(afterFirst.get("primaryColor").asText()).isEqualTo("#1565c0");
        assertThat(afterFirst.get("density").asText()).isEqualTo("COMFORTABLE");

        // Second PUT: only primaryColor - mode set by the first PUT must survive.
        ResponseEntity<String> secondPut = put("/organizations/me/theme", admin.accessToken(),
                Map.of("primaryColor", "#ff0000"));
        assertThat(secondPut.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode afterSecond = parse(secondPut.getBody());
        assertThat(afterSecond.get("primaryColor").asText()).isEqualTo("#ff0000");
        assertThat(afterSecond.get("mode").asText()).isEqualTo("DARK");
        assertThat(afterSecond.get("density").asText()).isEqualTo("COMFORTABLE");

        // A subsequent GET confirms both survive together.
        ResponseEntity<String> getResponse = get("/organizations/me/theme", admin.accessToken());
        JsonNode getBody = parse(getResponse.getBody());
        assertThat(getBody.get("primaryColor").asText()).isEqualTo("#ff0000");
        assertThat(getBody.get("mode").asText()).isEqualTo("DARK");
        assertThat(getBody.get("density").asText()).isEqualTo("COMFORTABLE");
    }

    @Test
    void employeeRole_getsForbiddenOnPut_butGetStillWorks() {
        AuthResponse admin = registerOrganization("Theme Role Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "themerole");

        ResponseEntity<String> putAttempt = put("/organizations/me/theme", employee.accessToken(),
                Map.of("primaryColor", "#00ff00"));
        assertThat(putAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> getAttempt = get("/organizations/me/theme", employee.accessToken());
        assertThat(getAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidPrimaryColor_isRejectedWithBadRequestAndFieldError() {
        AuthResponse admin = registerOrganization("Invalid Color Org");

        ResponseEntity<String> response = put("/organizations/me/theme", admin.accessToken(),
                Map.of("primaryColor", "not-a-hex-color"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("primaryColor");
    }

    @Test
    void invalidMode_isRejectedWithBadRequestAndFieldError() {
        AuthResponse admin = registerOrganization("Invalid Mode Org");

        ResponseEntity<String> response = put("/organizations/me/theme", admin.accessToken(),
                Map.of("mode", "PURPLE"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("mode");
    }

    @Test
    void invalidDensity_isRejectedWithBadRequestAndFieldError() {
        AuthResponse admin = registerOrganization("Invalid Density Org");

        ResponseEntity<String> response = put("/organizations/me/theme", admin.accessToken(),
                Map.of("density", "SPACIOUS"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("density");
    }

    @Test
    void employeeWithNoPreference_getMyThemePreference_returnsAllNullFields_notAnError() {
        AuthResponse admin = registerOrganization("No Preference Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "nopref");

        ResponseEntity<String> response = get("/employees/me/theme-preference", employee.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("primaryColor").isNull()).isTrue();
        assertThat(body.get("mode").isNull()).isTrue();
        assertThat(body.get("density").isNull()).isTrue();
    }

    @Test
    void anyAuthenticatedUser_canUpdateOwnThemePreference_andOnlyAffectsTheirOwnRecord() {
        AuthResponse admin = registerOrganization("Personal Preference Org");
        AuthResponse employeeA = createAndLoginEmployee(admin.accessToken(), "prefa");
        AuthResponse employeeB = createAndLoginEmployee(admin.accessToken(), "prefb");

        ResponseEntity<String> putA = put("/employees/me/theme-preference", employeeA.accessToken(),
                Map.of("mode", "DARK"));
        assertThat(putA.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> putB = put("/employees/me/theme-preference", employeeB.accessToken(),
                Map.of("mode", "LIGHT", "density", "COMPACT"));
        assertThat(putB.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode aPreference = parse(get("/employees/me/theme-preference", employeeA.accessToken()).getBody());
        assertThat(aPreference.get("mode").asText()).isEqualTo("DARK");
        assertThat(aPreference.get("density").isNull()).isTrue();

        JsonNode bPreference = parse(get("/employees/me/theme-preference", employeeB.accessToken()).getBody());
        assertThat(bPreference.get("mode").asText()).isEqualTo("LIGHT");
        assertThat(bPreference.get("density").asText()).isEqualTo("COMPACT");

        // Admin's own preference (never set) must remain untouched by either employee's PUT.
        JsonNode adminPreference = parse(get("/employees/me/theme-preference", admin.accessToken()).getBody());
        assertThat(adminPreference.get("mode").isNull()).isTrue();
    }

    @Test
    void tenantIsolation_orgAsThemeSettings_areUnaffectedByOrgBsPutCalls() {
        AuthResponse orgA = registerOrganization("Theme Isolation Org A");
        AuthResponse orgB = registerOrganization("Theme Isolation Org B");

        ResponseEntity<String> orgBPut = put("/organizations/me/theme", orgB.accessToken(),
                Map.of("primaryColor", "#123456", "mode", "DARK", "density", "COMPACT"));
        assertThat(orgBPut.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Org A must still see its own (default) theme, untouched by org B's PUT.
        JsonNode orgATheme = parse(get("/organizations/me/theme", orgA.accessToken()).getBody());
        assertThat(orgATheme.get("primaryColor").asText()).isEqualTo("#1565c0");
        assertThat(orgATheme.get("mode").asText()).isEqualTo("LIGHT");
        assertThat(orgATheme.get("density").asText()).isEqualTo("COMFORTABLE");

        JsonNode orgBTheme = parse(get("/organizations/me/theme", orgB.accessToken()).getBody());
        assertThat(orgBTheme.get("primaryColor").asText()).isEqualTo("#123456");
    }

    // ---- helpers ----

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
                "admin-" + UUID.randomUUID() + "@theme.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String emailPrefix) {
        String email = emailPrefix + "-" + UUID.randomUUID() + "@theme.test";
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
