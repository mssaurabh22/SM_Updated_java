package com.salesmanager.crm.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.lead.LeadStatus;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.scheduler.LapsedLeadJob;
import com.salesmanager.crm.scheduler.MissedVisitJob;
import com.salesmanager.crm.security.TenantSessionManager;
import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitRepository;
import com.salesmanager.crm.visit.VisitStatus;
import com.salesmanager.crm.visit.VisitType;
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
 * Phase 5: the Lead activity/journey timeline - GET /activity as both a single-Lead timeline
 * and a broader org-wide/personal feed, every ActivityType write call site (LeadService,
 * VisitService, visit.LeadVisitEventListener's auto-stub-Visit case, and the two scheduled
 * jobs), the EMPLOYEE-forced-to-own-leads visibility rule, newest-first ordering, and tenant
 * isolation. Follows the same Testcontainers/helper-method style as LeadReassignmentIT/
 * LeadEventIT/SchedulerIT.
 */
class ActivityLogIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private TenantSessionManager tenantSessionManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MissedVisitJob missedVisitJob;

    @Autowired
    private LapsedLeadJob lapsedLeadJob;

    @Test
    void leadCreation_producesExactlyOneLeadCreatedEntry() {
        AuthResponse admin = registerOrganization("Activity LeadCreated Org");
        Masters masters = loadMasters(admin.accessToken());

        String leadId = createLead(admin.accessToken(), masters, "Activity Created Co", "Contact AC",
                "9500000001", false);

        JsonNode entries = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=LEAD_CREATED");
        assertThat(entries.size()).isEqualTo(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("leadId").asText()).isEqualTo(leadId);
        assertThat(entry.get("ownerId").asText()).isEqualTo(admin.employeeId().toString());
        assertThat(entry.get("companyName").asText()).isEqualTo("Activity Created Co");
        assertThat(entry.get("actorId").asText()).isEqualTo(admin.employeeId().toString());
        assertThat(entry.get("description").asText()).isEqualTo("Lead created");
    }

    @Test
    void statusChange_producesEntryDescribingBothOldAndNewStatus() {
        AuthResponse admin = registerOrganization("Activity StatusChange Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Activity Status Co", "Contact AS",
                "9500000002", false);

        ResponseEntity<String> statusResponse = patch("/leads/" + leadId + "/status", admin.accessToken(),
                Map.of("status", "CONTACTED"));
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode entries = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=LEAD_STATUS_CHANGED");
        assertThat(entries.size()).isEqualTo(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("description").asText()).contains("NEW").contains("CONTACTED");
        assertThat(entry.get("actorId").asText()).isEqualTo(admin.employeeId().toString());
    }

    @Test
    void reassignment_producesEntryWithNewOwnerAndAdminActor() {
        AuthResponse admin = registerOrganization("Activity Reassign Org");
        AuthResponse newOwner = createAndLoginEmployee(admin.accessToken(), "activityNewOwner");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Activity Reassign Co", "Contact AR",
                "9500000003", false);

        ResponseEntity<String> reassignResponse = patch("/leads/" + leadId + "/reassign", admin.accessToken(),
                Map.of("newOwnerId", newOwner.employeeId().toString()));
        assertThat(reassignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode entries = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=LEAD_REASSIGNED");
        assertThat(entries.size()).isEqualTo(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("ownerId").asText()).isEqualTo(newOwner.employeeId().toString());
        assertThat(entry.get("actorId").asText()).isEqualTo(admin.employeeId().toString());
        assertThat(entry.get("description").asText()).isEqualTo("Lead reassigned");
    }

    @Test
    void visitCreation_producesVisitLoggedEntry_completingProducesSeparateVisitCompletedEntry() {
        AuthResponse admin = registerOrganization("Activity VisitLogged Org");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Activity Visit Co", "Contact AV",
                "9500000004", false);

        Map<String, Object> visitBody = minimalVisitBody(leadId, LocalDate.now());
        ResponseEntity<String> visitCreated = post("/visits", admin.accessToken(), visitBody);
        assertThat(visitCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String visitId = parse(visitCreated.getBody()).get("id").asText();

        JsonNode loggedEntries = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=VISIT_LOGGED");
        assertThat(loggedEntries.size()).isEqualTo(1);
        assertThat(loggedEntries.get(0).get("description").asText()).contains("FIELD");
        assertThat(loggedEntries.get(0).get("actorId").asText()).isEqualTo(admin.employeeId().toString());

        ResponseEntity<String> completeResponse = patch("/visits/" + visitId + "/status", admin.accessToken(),
                Map.of("status", "COMPLETED"));
        assertThat(completeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode completedEntries = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=VISIT_COMPLETED");
        assertThat(completedEntries.size()).isEqualTo(1);
        assertThat(completedEntries.get(0).get("description").asText()).isEqualTo("Visit marked completed");

        // The earlier VISIT_LOGGED entry is untouched/not duplicated by the completion.
        JsonNode loggedAfterCompletion = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=VISIT_LOGGED");
        assertThat(loggedAfterCompletion.size()).isEqualTo(1);
    }

    @Test
    void leadCreatedWithLogAsVisitToday_producesAdditionalVisitLoggedEntryWithNullActor() {
        AuthResponse admin = registerOrganization("Activity AutoVisit Org");
        Masters masters = loadMasters(admin.accessToken());

        String leadId = createLead(admin.accessToken(), masters, "Activity AutoVisit Co", "Contact AA",
                "9500000005", true);

        JsonNode loggedEntries = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=VISIT_LOGGED");
        assertThat(loggedEntries.size()).isEqualTo(1);
        JsonNode entry = loggedEntries.get(0);
        assertThat(entry.get("actorId").isNull()).isTrue();
        assertThat(entry.get("description").asText()).isEqualTo("Visit auto-scheduled");

        // The LEAD_CREATED entry (a human action) still has a real actor, for contrast.
        JsonNode createdEntries = activityContent(admin.accessToken(), "?leadId=" + leadId + "&type=LEAD_CREATED");
        assertThat(createdEntries.size()).isEqualTo(1);
        assertThat(createdEntries.get(0).get("actorId").asText()).isEqualTo(admin.employeeId().toString());
    }

    @Test
    void missedVisitJobAndLapsedLeadJob_produceActivityEntriesWithNullActor() {
        AuthResponse admin = registerOrganization("Activity Scheduler Org");
        AuthResponse owner = createAndLoginEmployee(admin.accessToken(), "activitySchedulerOwner");

        UUID missedLeadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Activity Missed Visit Co", "9500000006", LeadStatus.NEW, null);
        UUID overdueVisitId = seedVisit(admin.orgId(), missedLeadId, admin.employeeId(),
                LocalDate.now().minusDays(1), LocalTime.of(10, 0), VisitStatus.PLANNED);

        UUID lapsedLeadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Activity Lapsed Lead Co", "9500000007", LeadStatus.NEW, LocalDate.now().minusDays(1));

        missedVisitJob.flagMissedTimedVisits();
        lapsedLeadJob.flagLapsedLeads();

        JsonNode missedEntries = activityContent(admin.accessToken(), "?leadId=" + missedLeadId + "&type=VISIT_MISSED");
        assertThat(missedEntries.size()).isEqualTo(1);
        assertThat(missedEntries.get(0).get("actorId").isNull()).isTrue();
        assertThat(missedEntries.get(0).get("description").asText()).isEqualTo("Visit auto-flagged as missed");
        assertThat(missedEntries.get(0).get("ownerId").asText()).isEqualTo(owner.employeeId().toString());

        JsonNode lapsedEntries = activityContent(admin.accessToken(), "?leadId=" + lapsedLeadId + "&type=LEAD_LAPSED");
        assertThat(lapsedEntries.size()).isEqualTo(1);
        assertThat(lapsedEntries.get(0).get("actorId").isNull()).isTrue();
        assertThat(lapsedEntries.get(0).get("description").asText()).isEqualTo("Lead auto-flagged as lapsed");
        assertThat(lapsedEntries.get(0).get("ownerId").asText()).isEqualTo(owner.employeeId().toString());

        assertThat(overdueVisitId).isNotNull();
    }

    @Test
    void getActivity_scopedToLead_returnsNewestFirst() {
        AuthResponse admin = registerOrganization("Activity Order Org");
        AuthResponse newOwner = createAndLoginEmployee(admin.accessToken(), "activityOrderOwner");
        Masters masters = loadMasters(admin.accessToken());
        String leadId = createLead(admin.accessToken(), masters, "Activity Order Co", "Contact AO",
                "9500000008", false);

        patch("/leads/" + leadId + "/status", admin.accessToken(), Map.of("status", "CONTACTED"));
        patch("/leads/" + leadId + "/reassign", admin.accessToken(),
                Map.of("newOwnerId", newOwner.employeeId().toString()));

        JsonNode entries = activityContent(admin.accessToken(), "?leadId=" + leadId);
        assertThat(entries.size()).isEqualTo(3);
        // Newest-first: the LAST action performed (reassignment) must appear FIRST, and lead
        // creation (the FIRST action) must appear LAST.
        assertThat(entries.get(0).get("type").asText()).isEqualTo("LEAD_REASSIGNED");
        assertThat(entries.get(1).get("type").asText()).isEqualTo("LEAD_STATUS_CHANGED");
        assertThat(entries.get(2).get("type").asText()).isEqualTo("LEAD_CREATED");
    }

    @Test
    void getActivity_noLeadId_employeeSeesOnlyOwnLeads_adminSeesOrgWide_ownerIdFilterNarrows() {
        AuthResponse admin = registerOrganization("Activity Visibility Org");
        AuthResponse employeeA = createAndLoginEmployee(admin.accessToken(), "activityVisA");
        AuthResponse employeeB = createAndLoginEmployee(admin.accessToken(), "activityVisB");
        Masters masters = loadMasters(admin.accessToken());

        createLead(employeeA.accessToken(), masters, "Activity Vis A1 Co", "Contact VA1", "9500000009", false);
        createLead(employeeA.accessToken(), masters, "Activity Vis A2 Co", "Contact VA2", "9500000010", false);
        createLead(employeeB.accessToken(), masters, "Activity Vis B1 Co", "Contact VB1", "9500000011", false);

        // EMPLOYEE (A) sees only their own two leads' LEAD_CREATED entries, never B's.
        JsonNode employeeAView = activityContent(employeeA.accessToken(), "?type=LEAD_CREATED");
        assertThat(employeeAView.size()).isEqualTo(2);
        for (JsonNode entry : employeeAView) {
            assertThat(entry.get("ownerId").asText()).isEqualTo(employeeA.employeeId().toString());
        }

        // ADMIN, unfiltered, sees the whole org's three LEAD_CREATED entries.
        JsonNode adminView = activityContent(admin.accessToken(), "?type=LEAD_CREATED");
        assertThat(adminView.size()).isEqualTo(3);

        // ADMIN narrowed to employeeB's ownerId sees only B's single lead.
        JsonNode adminFilteredView = activityContent(admin.accessToken(),
                "?type=LEAD_CREATED&ownerId=" + employeeB.employeeId());
        assertThat(adminFilteredView.size()).isEqualTo(1);
        assertThat(adminFilteredView.get(0).get("ownerId").asText()).isEqualTo(employeeB.employeeId().toString());
    }

    @Test
    void tenantIsolation_activityEntriesNeverCrossOrgs() {
        AuthResponse orgA = registerOrganization("Activity Isolation Org A");
        AuthResponse orgB = registerOrganization("Activity Isolation Org B");
        Masters mastersA = loadMasters(orgA.accessToken());
        Masters mastersB = loadMasters(orgB.accessToken());

        String leadA = createLead(orgA.accessToken(), mastersA, "Activity Isolation Co A", "Contact IA",
                "9500000012", false);
        String leadB = createLead(orgB.accessToken(), mastersB, "Activity Isolation Co B", "Contact IB",
                "9500000013", false);

        JsonNode orgAActivity = activityContent(orgA.accessToken(), "");
        for (JsonNode entry : orgAActivity) {
            assertThat(entry.get("leadId").asText()).isNotEqualTo(leadB);
        }
        JsonNode orgBActivity = activityContent(orgB.accessToken(), "");
        for (JsonNode entry : orgBActivity) {
            assertThat(entry.get("leadId").asText()).isNotEqualTo(leadA);
        }

        // Org A cannot see org B's lead's activity even filtering explicitly by its id.
        JsonNode crossTenantAttempt = activityContent(orgA.accessToken(), "?leadId=" + leadB);
        assertThat(crossTenantAttempt.size()).isEqualTo(0);
    }

    // ---- seeding helpers (direct repository access, bypassing the public create APIs -
    // same technique/rationale as SchedulerIT's identical helpers) ----

    private UUID seedLead(UUID organizationId, UUID ownerId, UUID createdBy, String companyName, String contactNo,
                           LeadStatus status, LocalDate nextFollowupDate) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(txStatus -> {
            try {
                tenantSessionManager.activateTenant(organizationId);
                Lead lead = Lead.builder()
                        .companyName(companyName)
                        .contactPerson("Contact " + companyName)
                        .contactNo(contactNo)
                        .ownerId(ownerId)
                        .createdBy(createdBy)
                        .status(status)
                        .nextFollowupDate(nextFollowupDate)
                        .build();
                return leadRepository.saveAndFlush(lead).getId();
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private UUID seedVisit(UUID organizationId, UUID leadId, UUID createdBy, LocalDate visitDate,
                            LocalTime scheduledTime, VisitStatus status) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(txStatus -> {
            try {
                tenantSessionManager.activateTenant(organizationId);
                Visit visit = Visit.builder()
                        .leadId(leadId)
                        .visitDate(visitDate)
                        .scheduledTime(scheduledTime)
                        .visitType(VisitType.FIELD)
                        .status(status)
                        .createdBy(createdBy)
                        .build();
                return visitRepository.saveAndFlush(visit).getId();
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
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

    private String createLead(String token, Masters masters, String companyName, String contactPerson,
                               String contactNo, Boolean logAsVisitToday) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", companyName);
        body.put("contactPerson", contactPerson);
        body.put("contactNo", contactNo);
        body.put("cityId", masters.cityId);
        body.put("leadSourceId", masters.leadSourceId);
        body.put("industryId", masters.industryId);
        if (logAsVisitToday != null) {
            body.put("logAsVisitToday", logAsVisitToday);
        }
        ResponseEntity<String> response = post("/leads", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private Map<String, Object> minimalVisitBody(String leadId, LocalDate visitDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("leadId", leadId);
        body.put("visitDate", visitDate.toString());
        body.put("visitType", "FIELD");
        return body;
    }

    private String firstMasterId(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(response.getBody());
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
    }

    private JsonNode activityContent(String token, String query) {
        ResponseEntity<String> response = get("/activity" + query, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("content");
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
                "admin-" + UUID.randomUUID() + "@activity.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@activity.test";
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
