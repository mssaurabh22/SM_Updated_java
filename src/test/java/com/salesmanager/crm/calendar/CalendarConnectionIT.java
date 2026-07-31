package com.salesmanager.crm.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
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
 * CALENDAR_SYNC entitlement gating, the authorize-url endpoint's URL building (client_id/
 * redirect_uri/state present, one distinct URL per provider), and the callback's rejection of an
 * invalid/missing state - all testable without any real network call to Google/Microsoft, since
 * CalendarOAuthStateStore#consume rejects an unknown state before CalendarOAuthService ever
 * makes an outbound call. A genuinely successful code-exchange/token-refresh round-trip isn't
 * exercised here - that needs a real (or stubbed) provider, which this suite deliberately does
 * not stand up; see VisitCalendarSyncIT for how the FAILURE path is proven instead.
 */
class CalendarConnectionIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void nonEntitledOrg_getsForbidden_onEveryCalendarEndpoint() {
        AuthResponse admin = registerOrganization("Calendar Gating Org");

        assertThat(get("/calendar-connections/me", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/calendar-connections/GOOGLE/authorize-url", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete("/calendar-connections/me", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void entitledOrg_meReturnsNotConnected_whenNothingConnectedYet() {
        AuthResponse admin = registerOrganization("Calendar Status Org");
        grantCalendarSync(admin.orgId());

        ResponseEntity<String> response = get("/calendar-connections/me", admin.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("connected").asBoolean()).isFalse();
        assertThat(body.get("provider").isNull()).isTrue();
    }

    @Test
    void authorizeUrl_buildsADistinctUrlPerProvider_containingStateAndRedirectUri() {
        AuthResponse admin = registerOrganization("Calendar Authorize Org");
        grantCalendarSync(admin.orgId());

        ResponseEntity<String> googleResponse = get("/calendar-connections/GOOGLE/authorize-url", admin.accessToken());
        assertThat(googleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String googleUrl = parse(googleResponse.getBody()).get("url").asText();
        assertThat(googleUrl).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(googleUrl).contains("state=");

        ResponseEntity<String> outlookResponse = get("/calendar-connections/OUTLOOK/authorize-url", admin.accessToken());
        assertThat(outlookResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String outlookUrl = parse(outlookResponse.getBody()).get("url").asText();
        assertThat(outlookUrl).startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize");
        assertThat(outlookUrl).contains("state=");

        // Two independent calls mint two distinct, single-use state tokens.
        assertThat(googleUrl).isNotEqualTo(outlookUrl);
    }

    @Test
    void callback_withUnknownState_redirectsWithError_neverThrows() {
        AuthResponse admin = registerOrganization("Calendar Callback Org");
        grantCalendarSync(admin.orgId());

        // TestRestTemplate does not follow redirects by default (Spring Boot's own documented
        // behavior, precisely so a 3xx response's Location header can be inspected directly).
        ResponseEntity<String> response = get(
                "/calendar-connections/GOOGLE/callback?code=fake-code&state=" + UUID.randomUUID(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = response.getHeaders().getLocation().toString();
        assertThat(location).contains("calendar=error");
    }

    @Test
    void callback_withProviderMismatch_redirectsWithError() {
        AuthResponse admin = registerOrganization("Calendar Provider Mismatch Org");
        grantCalendarSync(admin.orgId());

        // Mint a real state for GOOGLE, then try to redeem it against the OUTLOOK callback -
        // CalendarOAuthStateStore#consume's defensive provider-mismatch check must reject this.
        String url = parse(get("/calendar-connections/GOOGLE/authorize-url", admin.accessToken())
                .getBody()).get("url").asText();
        String state = url.substring(url.indexOf("state=") + "state=".length());

        ResponseEntity<String> response = get(
                "/calendar-connections/OUTLOOK/callback?code=fake-code&state=" + state, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation().toString()).contains("calendar=error");
    }

    @Test
    void disconnect_isNoOp_whenNothingConnected() {
        AuthResponse admin = registerOrganization("Calendar Disconnect Org");
        grantCalendarSync(admin.orgId());

        assertThat(delete("/calendar-connections/me", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- helpers ----

    private void grantCalendarSync(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/CALENDAR_SYNC",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("action", "GRANT"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** token may be null - the callback endpoint is deliberately unauthenticated (see
     * SecurityConfig's permitAll for this exact path). TestRestTemplate does not follow
     * redirects by default, so a 3xx response's Location header can be asserted on directly. */
    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@calendarit.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
