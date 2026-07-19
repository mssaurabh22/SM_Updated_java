package com.salesmanager.crm.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import java.time.OffsetDateTime;
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
 * Proves Part A of the Employee Entitlement plan end-to-end (the entitlement/licensing
 * infrastructure itself, with no Leave/Attendance feature built yet): the internal grant/
 * revoke endpoint's shared-secret auth, tenant-scoped visibility of
 * GET /organizations/me/entitlements, RequireEntitlement's AOP enforcement (200 vs 403
 * FEATURE_NOT_ENTITLED) on the diagnostic /entitlement-check endpoint, revoke, past-expiry
 * behaving as not-entitled, and upsert-on-regrant. Follows the same Testcontainers/
 * helper-method style as ActivityLogIT.
 */
class EntitlementIT extends AbstractIntegrationTest {

    private static final String CODE = "EMPLOYEE_LEAVE_MANAGEMENT";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // Reads whatever platform.admin.key actually resolves to (the application.yml dev-only
    // fallback, since no PLATFORM_ADMIN_KEY env var is set in the test environment) rather
    // than hardcoding a value that could silently drift from the real configured default.
    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void internalGrant_correctKey_succeeds_missingOrWrongKey_401() {
        AuthResponse org = registerOrganization("Entitlement Auth Org");

        ResponseEntity<String> noKey = patchInternal(org.orgId(), CODE, grantBody(null, "manual grant"), null);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> wrongKey =
                patchInternal(org.orgId(), CODE, grantBody(null, "manual grant"), "not-the-real-key");
        assertThat(wrongKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> correctKey =
                patchInternal(org.orgId(), CODE, grantBody(null, "manual grant"), platformAdminKey);
        assertThat(correctKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode granted = parse(correctKey.getBody());
        assertThat(granted.get("entitlementCode").asText()).isEqualTo(CODE);
        assertThat(granted.get("active").asBoolean()).isTrue();
        assertThat(granted.get("grantedBy").asText()).isEqualTo("manual grant");
    }

    @Test
    void grantedOrg_seesEntitlement_inMeEndpoint_differentOrgDoesNot() {
        AuthResponse orgA = registerOrganization("Entitlement Visibility Org A");
        AuthResponse orgB = registerOrganization("Entitlement Visibility Org B");

        grant(orgA.orgId(), null, "trial");

        JsonNode orgAEntitlements = parse(get("/organizations/me/entitlements", orgA.accessToken()).getBody());
        assertThat(containsCode(orgAEntitlements, CODE)).isTrue();

        JsonNode orgBEntitlements = parse(get("/organizations/me/entitlements", orgB.accessToken()).getBody());
        assertThat(containsCode(orgBEntitlements, CODE)).isFalse();
    }

    @Test
    void diagnosticEndpoint_200ForEntitledOrg_403FeatureNotEntitledForNonEntitledOrg() {
        AuthResponse entitledOrg = registerOrganization("Entitlement Diagnostic Entitled Org");
        AuthResponse plainOrg = registerOrganization("Entitlement Diagnostic Plain Org");

        grant(entitledOrg.orgId(), null, "trial");

        ResponseEntity<String> entitledResponse =
                get("/entitlement-check/employee-leave-management", entitledOrg.accessToken());
        assertThat(entitledResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(entitledResponse.getBody()).get("entitled").asBoolean()).isTrue();

        ResponseEntity<String> plainResponse =
                get("/entitlement-check/employee-leave-management", plainOrg.accessToken());
        assertThat(plainResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode errorBody = parse(plainResponse.getBody());
        assertThat(errorBody.get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
        assertThat(errorBody.get("status").asInt()).isEqualTo(403);
    }

    @Test
    void revoke_removesAccess_fromDiagnosticEndpointAndMeListing() {
        AuthResponse org = registerOrganization("Entitlement Revoke Org");
        grant(org.orgId(), null, "trial");
        assertThat(get("/entitlement-check/employee-leave-management", org.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> revokeResponse = patchInternal(org.orgId(), CODE, revokeBody(), platformAdminKey);
        assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> afterRevoke = get("/entitlement-check/employee-leave-management", org.accessToken());
        assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(afterRevoke.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");

        JsonNode meListing = parse(get("/organizations/me/entitlements", org.accessToken()).getBody());
        assertThat(containsCode(meListing, CODE)).isFalse();
    }

    @Test
    void pastExpiresAt_behavesAsNotEntitled_evenWithoutExplicitRevoke() {
        AuthResponse org = registerOrganization("Entitlement Expiry Org");
        grant(org.orgId(), OffsetDateTime.now().minusDays(1), "expired trial");

        ResponseEntity<String> diagnostic = get("/entitlement-check/employee-leave-management", org.accessToken());
        assertThat(diagnostic.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(diagnostic.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");

        JsonNode meListing = parse(get("/organizations/me/entitlements", org.accessToken()).getBody());
        assertThat(containsCode(meListing, CODE)).isFalse();
    }

    @Test
    void reGrantAfterRevoke_upsertsRatherThanDuplicating_andRestoresAccess() {
        AuthResponse org = registerOrganization("Entitlement Regrant Org");
        grant(org.orgId(), null, "first grant");
        ResponseEntity<String> revokeResponse = patchInternal(org.orgId(), CODE, revokeBody(), platformAdminKey);
        assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/entitlement-check/employee-leave-management", org.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> reGrant =
                patchInternal(org.orgId(), CODE, grantBody(null, "second grant"), platformAdminKey);
        assertThat(reGrant.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode reGranted = parse(reGrant.getBody());
        assertThat(reGranted.get("active").asBoolean()).isTrue();
        assertThat(reGranted.get("grantedBy").asText()).isEqualTo("second grant");

        assertThat(get("/entitlement-check/employee-leave-management", org.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // The internal listing shows exactly one row for this org+code - the upsert never
        // created a duplicate that would have violated the (organization_id, entitlement_code)
        // unique constraint.
        ResponseEntity<String> listing = getInternal(org.orgId(), platformAdminKey);
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(listing.getBody());
        long matching = 0;
        for (JsonNode entry : entries) {
            if (entry.get("entitlementCode").asText().equals(CODE)) {
                matching++;
            }
        }
        assertThat(matching).isEqualTo(1);
    }

    // ---- helpers ----

    private void grant(UUID orgId, OffsetDateTime expiresAt, String grantedBy) {
        ResponseEntity<String> response = patchInternal(orgId, CODE, grantBody(expiresAt, grantedBy), platformAdminKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private boolean containsCode(JsonNode array, String code) {
        for (JsonNode node : array) {
            if (node.asText().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> grantBody(OffsetDateTime expiresAt, String grantedBy) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt.toString());
        }
        if (grantedBy != null) {
            body.put("grantedBy", grantedBy);
        }
        return body;
    }

    private Map<String, Object> revokeBody() {
        return Map.of("action", "REVOKE");
    }

    private ResponseEntity<String> patchInternal(UUID orgId, String code, Object body, String platformKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (platformKey != null) {
            headers.set("X-Platform-Key", platformKey);
        }
        return restTemplate.exchange(baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + code,
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> getInternal(UUID orgId, String platformKey) {
        HttpHeaders headers = new HttpHeaders();
        if (platformKey != null) {
            headers.set("X-Platform-Key", platformKey);
        }
        return restTemplate.exchange(baseUrl() + "/internal/organizations/" + orgId + "/entitlements",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@entitlement.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
