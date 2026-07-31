package com.salesmanager.crm.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.security.TenantSessionManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PUSH_NOTIFICATIONS entitlement gating on /device-tokens, register/re-register (upsert, not
 * duplicate) and unregister (employee-scoped, never cross-employee) behavior. Does not exercise
 * an actual Firebase send - FIREBASE_SERVICE_ACCOUNT_JSON is never set in tests, so
 * PushNotificationService#enabled is always false and PushNotificationEventListener's push call
 * is a guaranteed no-op; the notification-creation trigger tests below only prove the
 * NotificationCreatedEvent wiring never breaks the triggering request, not that a push was
 * actually delivered.
 */
class DeviceTokenIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private TenantSessionManager tenantSessionManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void nonEntitledOrg_getsForbidden_onDeviceTokenRegister() {
        AuthResponse admin = registerOrganization("Device Token Gating Org");

        ResponseEntity<String> response = post("/device-tokens", admin.accessToken(),
                Map.of("token", "tok-" + UUID.randomUUID(), "platform", "WEB"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(response.getBody()).get("error").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
    }

    @Test
    void entitledOrg_registersToken_reRegisteringUpserts_unregisterRemovesIt() {
        AuthResponse admin = registerOrganization("Device Token Org");
        grantPushNotifications(admin.orgId());
        String token = "tok-" + UUID.randomUUID();

        ResponseEntity<String> registerResponse = post("/device-tokens", admin.accessToken(),
                Map.of("token", token, "platform", "WEB"));
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<DeviceToken> afterFirstRegister = findByEmployeeIdInTenant(admin.orgId(), admin.employeeId());
        assertThat(afterFirstRegister).hasSize(1);
        assertThat(afterFirstRegister.get(0).getPlatform()).isEqualTo(DevicePlatform.WEB);

        // Re-registering the SAME token (e.g. a browser tab re-confirming its token) upserts in
        // place - still exactly one row, not a duplicate that would violate the unique constraint.
        ResponseEntity<String> reRegisterResponse = post("/device-tokens", admin.accessToken(),
                Map.of("token", token, "platform", "WEB"));
        assertThat(reRegisterResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(findByEmployeeIdInTenant(admin.orgId(), admin.employeeId())).hasSize(1);

        ResponseEntity<String> unregisterResponse = delete("/device-tokens/" + token, admin.accessToken());
        assertThat(unregisterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(findByTokenInTenant(admin.orgId(), token)).isEmpty();
    }

    @Test
    void unregister_isScopedToTheCallersOwnToken_neverAColleagues() {
        AuthResponse admin = registerOrganization("Device Token Ownership Org");
        grantPushNotifications(admin.orgId());
        AuthResponse colleague = createAndLoginEmployee(admin.accessToken(), "deviceTokenColleague");
        String colleagueToken = "tok-" + UUID.randomUUID();
        assertThat(post("/device-tokens", colleague.accessToken(),
                Map.of("token", colleagueToken, "platform", "WEB")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // admin attempts to unregister a token that belongs to colleague, not themself.
        ResponseEntity<String> response = delete("/device-tokens/" + colleagueToken, admin.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Silently no-op, not an error - and critically, the colleague's token is untouched.
        assertThat(findByTokenInTenant(admin.orgId(), colleagueToken)).isPresent();
    }

    @Test
    void notificationCreation_withPushEntitled_doesNotBreakTheTriggeringRequest() {
        AuthResponse admin = registerOrganization("Push Trigger Org");
        grantPushNotifications(admin.orgId());
        AuthResponse recipient = createAndLoginEmployee(admin.accessToken(), "pushRecipient");
        assertThat(post("/device-tokens", recipient.accessToken(),
                Map.of("token", "tok-" + UUID.randomUUID(), "platform", "WEB")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // LEAD_REASSIGNED is never entitlement-gated itself (see notificationFormat.ts's
        // NOTIFICATION_TYPE_ENTITLEMENT map) - reused here purely as a real notification-creation
        // trigger, same as NotificationIT does for its own unrelated assertions.
        String leadId = createLead(recipient.accessToken(), "Push Trigger Co", "Contact", "9600000099");
        ResponseEntity<String> reassign = patch("/leads/" + leadId + "/reassign", admin.accessToken(),
                Map.of("newOwnerId", admin.employeeId().toString()));

        // The AFTER_COMMIT push listener runs as part of this same request/response cycle in
        // tests (no separate thread) - if it threw, this response would never come back 200.
        assertThat(reassign.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- helpers ----

    /** Direct repository reads need a manually-activated tenant transaction outside a real HTTP
     * request - same pattern SchedulerIT#seedVisit uses (mirrors TenantSessionManager's own use
     * in AuthService) - since neither the Hibernate tenantFilter nor Postgres RLS's
     * app.current_org session variable are set on this test thread otherwise. */
    private List<DeviceToken> findByEmployeeIdInTenant(UUID organizationId, UUID employeeId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(txStatus -> {
            try {
                tenantSessionManager.activateTenant(organizationId);
                return deviceTokenRepository.findByEmployeeId(employeeId);
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private Optional<DeviceToken> findByTokenInTenant(UUID organizationId, String token) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(txStatus -> {
            try {
                tenantSessionManager.activateTenant(organizationId);
                return deviceTokenRepository.findByToken(token);
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private void grantPushNotifications(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/PUSH_NOTIFICATIONS",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("action", "GRANT"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createLead(String token, String companyName, String contactPerson, String contactNo) {
        String cityId = firstMasterId(token, "CITY");
        String leadSourceId = firstMasterId(token, "LEAD_SOURCE");
        String industryId = firstMasterId(token, "INDUSTRY");
        ResponseEntity<String> response = post("/leads", token, Map.of(
                "companyName", companyName,
                "contactPerson", contactPerson,
                "contactNo", contactNo,
                "cityId", cityId,
                "leadSourceId", leadSourceId,
                "industryId", industryId,
                "logAsVisitToday", false));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private String firstMasterId(String token, String type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(response.getBody());
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
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
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
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
                "admin-" + UUID.randomUUID() + "@devicetoken.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@devicetoken.test";
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
