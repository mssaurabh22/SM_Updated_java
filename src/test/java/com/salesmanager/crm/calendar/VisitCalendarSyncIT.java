package com.salesmanager.crm.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.security.TenantSessionManager;
import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
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
 * Proves the event-driven wiring (VisitService -> VisitCalendarSyncEvent -> CalendarSyncEventListener
 * -> CalendarSyncService) end to end WITHOUT a real Google/Outlook account: a CalendarConnection
 * is seeded directly (bypassing the OAuth handshake entirely, which CalendarConnectionIT already
 * covers up to the point a real provider would be needed), so CalendarSyncService inevitably
 * attempts a real outbound call with a fake access token and gets rejected/unreachable - CAUGHT
 * by its own try/catch (see the class javadoc), never rolling back the Visit save that triggered
 * it. This is deliberately the failure path, not the happy path - see the plan's Section 15
 * verification notes for why a genuine success round-trip isn't exercised in this suite.
 */
class VisitCalendarSyncIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;

    @Autowired
    private TokenEncryptionService tokenEncryptionService;

    @Autowired
    private TenantSessionManager tenantSessionManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void visitCreate_withCalendarSyncEntitledAndConnectionPresent_attemptsSyncAndRecordsFailureGracefully() {
        AuthResponse admin = registerOrganization("Calendar Sync Org");
        grantCalendarSync(admin.orgId());
        seedCalendarConnection(admin.orgId(), admin.employeeId());

        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Calendar Sync Co", "Contact", "9700000001");
        String visitId = createVisit(admin.accessToken(), leadId, LocalDate.now());

        Visit visit = findVisitInTenant(admin.orgId(), visitId).orElseThrow();
        // A fake access token against the real Google endpoint (5s timeout, see
        // CalendarHttpClient) can only ever fail - proving the listener ran, attempted a sync,
        // and gracefully recorded the outcome instead of leaving the Visit's own save untouched.
        assertThat(visit.getCalendarSyncStatus()).isEqualTo("FAILED");
        assertThat(visit.getCalendarSyncError()).isNotBlank();
        assertThat(visit.getExternalCalendarEventId()).isNull();

        // The triggering request itself must still have succeeded - a calendar sync failure
        // never rolls back or otherwise affects the Visit create response.
        assertThat(getVisitStatus(admin.accessToken(), visitId)).isEqualTo("PLANNED");
    }

    @Test
    void visitCreate_withCalendarSyncNotEntitled_doesNotAttemptSync() {
        AuthResponse admin = registerOrganization("Calendar Sync Not Entitled Org");
        // Deliberately NOT granted - and a connection exists anyway, to prove entitlement (not
        // just connection presence) gates whether a sync is attempted at all.
        seedCalendarConnection(admin.orgId(), admin.employeeId());

        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "No Entitlement Co", "Contact", "9700000002");
        String visitId = createVisit(admin.accessToken(), leadId, LocalDate.now());

        Visit visit = findVisitInTenant(admin.orgId(), visitId).orElseThrow();
        assertThat(visit.getCalendarSyncStatus()).isNull();
        assertThat(visit.getExternalCalendarEventId()).isNull();
    }

    @Test
    void visitCreate_withNoConnection_doesNotAttemptSync_evenWhenEntitled() {
        AuthResponse admin = registerOrganization("Calendar Sync No Connection Org");
        grantCalendarSync(admin.orgId());
        // No CalendarConnection seeded at all.

        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "No Connection Co", "Contact", "9700000003");
        String visitId = createVisit(admin.accessToken(), leadId, LocalDate.now());

        Visit visit = findVisitInTenant(admin.orgId(), visitId).orElseThrow();
        assertThat(visit.getCalendarSyncStatus()).isNull();
    }

    // ---- helpers ----

    private void seedCalendarConnection(UUID organizationId, UUID employeeId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(txStatus -> {
            try {
                tenantSessionManager.activateTenant(organizationId);
                CalendarConnection connection = CalendarConnection.builder()
                        .employeeId(employeeId)
                        .provider(CalendarProvider.GOOGLE)
                        .accessTokenEncrypted(tokenEncryptionService.encrypt("fake-access-token"))
                        .refreshTokenEncrypted(tokenEncryptionService.encrypt("fake-refresh-token"))
                        // Far in the future - getValidAccessToken must not attempt a (also-doomed)
                        // refresh call first, isolating this test to exactly the event-creation call.
                        .tokenExpiresAt(OffsetDateTime.now().plusDays(1))
                        .connectedAt(OffsetDateTime.now())
                        .build();
                calendarConnectionRepository.saveAndFlush(connection);
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private Optional<Visit> findVisitInTenant(UUID organizationId, String visitId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(txStatus -> {
            try {
                tenantSessionManager.activateTenant(organizationId);
                return visitRepository.findById(UUID.fromString(visitId));
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private void grantCalendarSync(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/CALENDAR_SYNC",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("action", "GRANT"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

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

    private String createVisit(String token, String leadId, LocalDate visitDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("leadId", leadId);
        body.put("visitDate", visitDate.toString());
        body.put("visitType", "FIELD");
        ResponseEntity<String> response = post("/visits", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private String getVisitStatus(String token, String visitId) {
        ResponseEntity<String> response = get("/visits/" + visitId, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("status").asText();
    }

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@visitcalendarsync.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
