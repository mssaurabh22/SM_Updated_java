package com.salesmanager.crm.scheduler;

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
import com.salesmanager.crm.security.TenantSessionManager;
import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitRepository;
import com.salesmanager.crm.visit.VisitStatus;
import com.salesmanager.crm.visit.VisitType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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
 * Phase 4: MissedVisitJob's two sweeps (timed/untimed) and LapsedLeadJob, exercised by
 * autowiring the job beans and invoking their {@code @Scheduled} methods directly/synchronously
 * (no real wall-clock scheduling in a test). Visits/Leads that must already be "overdue" are
 * seeded directly via VisitRepository/LeadRepository (inside a manually-activated tenant
 * transaction, mirroring TenantSessionManager's own use in AuthService) rather than through the
 * public POST APIs, since VisitCreateRequest#visitDate carries {@code @NotPastDate} and would
 * reject a deliberately-overdue visit outright - LeadCreateRequest#nextFollowupDate has no such
 * constraint, but seeding leads the same direct way keeps this suite from also depending on
 * LeadService's unrelated auto-stub-Visit event machinery (a Lead created via the real API with
 * a past nextFollowupDate would also spawn a PLANNED stub Visit dated to that same past day,
 * which would itself be an unrelated MISSED candidate for the untimed sweep).
 */
class SchedulerIT extends AbstractIntegrationTest {

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
    void overdueTimedVisit_flipsToMissed_andNotifiesEveryAdmin_nonOverdueVisitUntouched() {
        AuthResponse admin = registerOrganization("Scheduler Timed Org");
        AuthResponse secondAdmin = createAndLoginEmployee(admin.accessToken(), "timedSecondAdmin", Role.ADMIN);
        AuthResponse owner = createAndLoginEmployee(admin.accessToken(), "timedOwner", Role.EMPLOYEE);

        UUID leadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Timed Sweep Co", "9000000001", LeadStatus.NEW, null);

        // Overdue by more than 30 minutes regardless of time-of-day (visit_date is yesterday).
        UUID overdueVisitId = seedVisit(admin.orgId(), leadId, admin.employeeId(),
                LocalDate.now().minusDays(1), LocalTime.of(10, 0), VisitStatus.PLANNED);
        // Comfortably in the future - must NOT be touched.
        UUID futureVisitId = seedVisit(admin.orgId(), leadId, admin.employeeId(),
                LocalDate.now().plusDays(1), LocalTime.NOON, VisitStatus.PLANNED);

        missedVisitJob.flagMissedTimedVisits();

        assertThat(getVisitStatus(admin.accessToken(), overdueVisitId)).isEqualTo("MISSED");
        assertThat(getVisitStatus(admin.accessToken(), futureVisitId)).isEqualTo("PLANNED");

        // Every ADMIN in the org gets escalated - not just one.
        List<JsonNode> adminNotifications = notificationsOfType(admin.accessToken(), "VISIT_MISSED");
        assertThat(adminNotifications).hasSize(1);
        assertThat(adminNotifications.get(0).get("payload").asText())
                .contains(overdueVisitId.toString()).contains(leadId.toString());

        List<JsonNode> secondAdminNotifications = notificationsOfType(secondAdmin.accessToken(), "VISIT_MISSED");
        assertThat(secondAdminNotifications).hasSize(1);
        assertThat(secondAdminNotifications.get(0).get("payload").asText()).contains(overdueVisitId.toString());

        // The visit's own owner (the parent Lead's owner) is notified directly too, even though
        // they're a plain EMPLOYEE, not an ADMIN - see overdueTimedVisit_ownedByEmployee_* below
        // for the dedicated test of this.
        List<JsonNode> ownerNotifications = notificationsOfType(owner.accessToken(), "VISIT_MISSED");
        assertThat(ownerNotifications).hasSize(1);
        assertThat(ownerNotifications.get(0).get("payload").asText()).contains(overdueVisitId.toString());
    }

    @Test
    void overdueTimedVisit_ownedByEmployee_notifiesBothTheOwnerAndTheAdmin() {
        AuthResponse admin = registerOrganization("Scheduler Owner Notify Org");
        AuthResponse owner = createAndLoginEmployee(admin.accessToken(), "ownerNotifyOwner", Role.EMPLOYEE);

        UUID leadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Owner Notify Co", "9000000101", LeadStatus.NEW, null);
        UUID overdueVisitId = seedVisit(admin.orgId(), leadId, admin.employeeId(),
                LocalDate.now().minusDays(1), LocalTime.of(10, 0), VisitStatus.PLANNED);

        missedVisitJob.flagMissedTimedVisits();

        assertThat(getVisitStatus(admin.accessToken(), overdueVisitId)).isEqualTo("MISSED");

        // The org's Admin is notified (management escalation)...
        List<JsonNode> adminNotifications = notificationsOfType(admin.accessToken(), "VISIT_MISSED");
        assertThat(adminNotifications).hasSize(1);
        assertThat(adminNotifications.get(0).get("payload").asText()).contains(overdueVisitId.toString());

        // ...AND the visit's own owner (the parent Lead's owner) is notified directly too -
        // two distinct recipients for the same missed visit, neither missing nor duplicated.
        List<JsonNode> ownerNotifications = notificationsOfType(owner.accessToken(), "VISIT_MISSED");
        assertThat(ownerNotifications).hasSize(1);
        assertThat(ownerNotifications.get(0).get("payload").asText()).contains(overdueVisitId.toString());
    }

    @Test
    void overdueTimedVisit_ownedByTheOrgsOnlyAdmin_notifiesThemExactlyOnce_noDuplicate() {
        AuthResponse admin = registerOrganization("Scheduler Admin Owner Org");

        // The Admin themself owns this lead (and thus its visit) - they must not receive two
        // VISIT_MISSED notifications for the same visit (once as Admin, once as owner).
        UUID leadId = seedLead(admin.orgId(), admin.employeeId(), admin.employeeId(),
                "Admin Owner Co", "9000000102", LeadStatus.NEW, null);
        UUID overdueVisitId = seedVisit(admin.orgId(), leadId, admin.employeeId(),
                LocalDate.now().minusDays(1), LocalTime.of(10, 0), VisitStatus.PLANNED);

        missedVisitJob.flagMissedTimedVisits();

        assertThat(getVisitStatus(admin.accessToken(), overdueVisitId)).isEqualTo("MISSED");

        List<JsonNode> adminNotifications = notificationsOfType(admin.accessToken(), "VISIT_MISSED");
        assertThat(adminNotifications).hasSize(1);
        assertThat(adminNotifications.get(0).get("payload").asText()).contains(overdueVisitId.toString());
    }

    @Test
    void overdueUntimedVisit_untouchedByTimedSweep_flipsToMissedOnlyViaNightlyUntimedSweep() {
        AuthResponse admin = registerOrganization("Scheduler Untimed Org");
        AuthResponse owner = createAndLoginEmployee(admin.accessToken(), "untimedOwner", Role.EMPLOYEE);
        UUID leadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Untimed Sweep Co", "9000000002", LeadStatus.NEW, null);

        // Date-only (no scheduled_time), from a day that has fully passed.
        UUID untimedVisitId = seedVisit(admin.orgId(), leadId, admin.employeeId(),
                LocalDate.now().minusDays(1), null, VisitStatus.PLANNED);

        // The 5-minute timed sweep must ignore it (scheduled_time IS NULL fails its filter).
        missedVisitJob.flagMissedTimedVisits();
        assertThat(getVisitStatus(admin.accessToken(), untimedVisitId)).isEqualTo("PLANNED");

        // The nightly untimed sweep flips it.
        missedVisitJob.flagMissedUntimedVisits();
        assertThat(getVisitStatus(admin.accessToken(), untimedVisitId)).isEqualTo("MISSED");

        List<JsonNode> adminNotifications = notificationsOfType(admin.accessToken(), "VISIT_MISSED");
        assertThat(adminNotifications).hasSize(1);
        assertThat(adminNotifications.get(0).get("payload").asText()).contains(untimedVisitId.toString());
    }

    @Test
    void overdueLead_flipsToLapsed_andNotifiesOwnerOnly_terminalStatusLeadsNeverResurrected() {
        AuthResponse admin = registerOrganization("Scheduler Lapsed Org");
        AuthResponse owner = createAndLoginEmployee(admin.accessToken(), "lapsedOwner", Role.EMPLOYEE);

        UUID overdueLeadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Lapsed Sweep Co", "9000000003", LeadStatus.NEW, LocalDate.now().minusDays(1));
        UUID futureFollowupLeadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Not Yet Due Co", "9000000004", LeadStatus.NEW, LocalDate.now().plusDays(3));
        UUID lostLeadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Already Lost Co", "9000000005", LeadStatus.LOST, LocalDate.now().minusDays(1));
        UUID closedWonLeadId = seedLead(admin.orgId(), owner.employeeId(), admin.employeeId(),
                "Already Won Co", "9000000006", LeadStatus.CLOSED_WON, LocalDate.now().minusDays(1));

        lapsedLeadJob.flagLapsedLeads();

        assertThat(getLeadStatus(admin.accessToken(), overdueLeadId)).isEqualTo("LAPSED");
        assertThat(getLeadStatus(admin.accessToken(), futureFollowupLeadId)).isEqualTo("NEW");
        assertThat(getLeadStatus(admin.accessToken(), lostLeadId)).isEqualTo("LOST");
        assertThat(getLeadStatus(admin.accessToken(), closedWonLeadId)).isEqualTo("CLOSED_WON");

        // Owner gets exactly one LEAD_LAPSED notification, for the one lead that actually lapsed.
        List<JsonNode> ownerNotifications = notificationsOfType(owner.accessToken(), "LEAD_LAPSED");
        assertThat(ownerNotifications).hasSize(1);
        assertThat(ownerNotifications.get(0).get("payload").asText()).contains(overdueLeadId.toString());
    }

    @Test
    void lapsedLeads_ownersStillNotifiedIndividually_andEachOrgsAdminsGetExactlyOneScopedDigest() {
        AuthResponse orgA = registerOrganization("Scheduler Digest Org A");
        AuthResponse orgASecondAdmin = createAndLoginEmployee(orgA.accessToken(), "digestAAdmin2", Role.ADMIN);
        AuthResponse orgAOwner1 = createAndLoginEmployee(orgA.accessToken(), "digestAOwner1", Role.EMPLOYEE);
        AuthResponse orgAOwner2 = createAndLoginEmployee(orgA.accessToken(), "digestAOwner2", Role.EMPLOYEE);

        AuthResponse orgB = registerOrganization("Scheduler Digest Org B");
        AuthResponse orgBOwner = createAndLoginEmployee(orgB.accessToken(), "digestBOwner", Role.EMPLOYEE);

        // Org A: two leads lapse, owned by two different employees.
        UUID leadA1 = seedLead(orgA.orgId(), orgAOwner1.employeeId(), orgA.employeeId(),
                "Digest Co A1", "9000000201", LeadStatus.NEW, LocalDate.now().minusDays(1));
        UUID leadA2 = seedLead(orgA.orgId(), orgAOwner2.employeeId(), orgA.employeeId(),
                "Digest Co A2", "9000000202", LeadStatus.NEW, LocalDate.now().minusDays(1));

        // Org B: one lead lapses.
        UUID leadB = seedLead(orgB.orgId(), orgBOwner.employeeId(), orgB.employeeId(),
                "Digest Co B", "9000000203", LeadStatus.NEW, LocalDate.now().minusDays(1));

        lapsedLeadJob.flagLapsedLeads();

        assertThat(getLeadStatus(orgA.accessToken(), leadA1)).isEqualTo("LAPSED");
        assertThat(getLeadStatus(orgA.accessToken(), leadA2)).isEqualTo("LAPSED");
        assertThat(getLeadStatus(orgB.accessToken(), leadB)).isEqualTo("LAPSED");

        // Existing per-lead owner notification is unchanged: each owner gets exactly one.
        assertThat(notificationsOfType(orgAOwner1.accessToken(), "LEAD_LAPSED")).hasSize(1);
        assertThat(notificationsOfType(orgAOwner2.accessToken(), "LEAD_LAPSED")).hasSize(1);
        assertThat(notificationsOfType(orgBOwner.accessToken(), "LEAD_LAPSED")).hasSize(1);

        // Every Admin in Org A gets exactly ONE digest for this run - not two, even though two
        // leads lapsed there - and it's scoped to Org A's count of 2.
        for (String token : List.of(orgA.accessToken(), orgASecondAdmin.accessToken())) {
            List<JsonNode> digests = notificationsOfType(token, "LEAD_LAPSED_DIGEST");
            assertThat(digests).hasSize(1);
            assertThat(parse(digests.get(0).get("payload").asText()).get("count").asInt()).isEqualTo(2);
        }

        // Org B's admin gets their own single digest, scoped to just Org B's count of 1 - not
        // contaminated by Org A's batch.
        List<JsonNode> orgBDigests = notificationsOfType(orgB.accessToken(), "LEAD_LAPSED_DIGEST");
        assertThat(orgBDigests).hasSize(1);
        assertThat(parse(orgBDigests.get(0).get("payload").asText()).get("count").asInt()).isEqualTo(1);

        // Owners (plain EMPLOYEEs) never receive the Admin-only digest.
        assertThat(notificationsOfType(orgAOwner1.accessToken(), "LEAD_LAPSED_DIGEST")).isEmpty();
        assertThat(notificationsOfType(orgBOwner.accessToken(), "LEAD_LAPSED_DIGEST")).isEmpty();
    }

    @Test
    void tenantIsolation_twoOrgsSweptInOnePass_bothTransitionCorrectly_noCrossContamination() {
        AuthResponse orgA = registerOrganization("Scheduler Isolation Org A");
        AuthResponse orgASecondAdmin = createAndLoginEmployee(orgA.accessToken(), "isoAAdmin2", Role.ADMIN);
        AuthResponse orgAOwner = createAndLoginEmployee(orgA.accessToken(), "isoAOwner", Role.EMPLOYEE);

        AuthResponse orgB = registerOrganization("Scheduler Isolation Org B");
        AuthResponse orgBSecondAdmin = createAndLoginEmployee(orgB.accessToken(), "isoBAdmin2", Role.ADMIN);
        AuthResponse orgBOwner = createAndLoginEmployee(orgB.accessToken(), "isoBOwner", Role.EMPLOYEE);

        UUID leadA = seedLead(orgA.orgId(), orgAOwner.employeeId(), orgA.employeeId(),
                "Isolation Co A", "9000000007", LeadStatus.NEW, LocalDate.now().minusDays(1));
        UUID visitA = seedVisit(orgA.orgId(), leadA, orgA.employeeId(),
                LocalDate.now().minusDays(1), LocalTime.of(9, 0), VisitStatus.PLANNED);

        UUID leadB = seedLead(orgB.orgId(), orgBOwner.employeeId(), orgB.employeeId(),
                "Isolation Co B", "9000000008", LeadStatus.NEW, LocalDate.now().minusDays(1));
        UUID visitB = seedVisit(orgB.orgId(), leadB, orgB.employeeId(),
                LocalDate.now().minusDays(1), LocalTime.of(9, 0), VisitStatus.PLANNED);

        // One pass of each job, across both orgs at once - this is exactly the cross-tenant
        // batch design under test (a single native UPDATE spanning all orgs' rows).
        missedVisitJob.flagMissedTimedVisits();
        lapsedLeadJob.flagLapsedLeads();

        assertThat(getVisitStatus(orgA.accessToken(), visitA)).isEqualTo("MISSED");
        assertThat(getVisitStatus(orgB.accessToken(), visitB)).isEqualTo("MISSED");
        assertThat(getLeadStatus(orgA.accessToken(), leadA)).isEqualTo("LAPSED");
        assertThat(getLeadStatus(orgB.accessToken(), leadB)).isEqualTo("LAPSED");

        // Org A's admins/owner only ever hear about Org A's visit/lead.
        for (String token : List.of(orgA.accessToken(), orgASecondAdmin.accessToken())) {
            List<JsonNode> notifications = notificationsOfType(token, "VISIT_MISSED");
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).get("payload").asText())
                    .contains(visitA.toString()).doesNotContain(visitB.toString());
        }
        List<JsonNode> orgALeadNotifications = notificationsOfType(orgAOwner.accessToken(), "LEAD_LAPSED");
        assertThat(orgALeadNotifications).hasSize(1);
        assertThat(orgALeadNotifications.get(0).get("payload").asText())
                .contains(leadA.toString()).doesNotContain(leadB.toString());

        // Org B's admins/owner only ever hear about Org B's visit/lead.
        for (String token : List.of(orgB.accessToken(), orgBSecondAdmin.accessToken())) {
            List<JsonNode> notifications = notificationsOfType(token, "VISIT_MISSED");
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).get("payload").asText())
                    .contains(visitB.toString()).doesNotContain(visitA.toString());
        }
        List<JsonNode> orgBLeadNotifications = notificationsOfType(orgBOwner.accessToken(), "LEAD_LAPSED");
        assertThat(orgBLeadNotifications).hasSize(1);
        assertThat(orgBLeadNotifications.get(0).get("payload").asText())
                .contains(leadB.toString()).doesNotContain(leadA.toString());

        // Each org's owner is notified about their own visit (they own leadA/leadB respectively)
        // but never sees the other org's visit escalation.
        List<JsonNode> orgAOwnerVisitNotifications = notificationsOfType(orgAOwner.accessToken(), "VISIT_MISSED");
        assertThat(orgAOwnerVisitNotifications).hasSize(1);
        assertThat(orgAOwnerVisitNotifications.get(0).get("payload").asText())
                .contains(visitA.toString()).doesNotContain(visitB.toString());

        List<JsonNode> orgBOwnerVisitNotifications = notificationsOfType(orgBOwner.accessToken(), "VISIT_MISSED");
        assertThat(orgBOwnerVisitNotifications).hasSize(1);
        assertThat(orgBOwnerVisitNotifications.get(0).get("payload").asText())
                .contains(visitB.toString()).doesNotContain(visitA.toString());
    }

    // ---- seeding helpers (direct repository access, bypassing the public create APIs) ----

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

    // ---- REST helpers, same style as LeadReassignmentIT/VisitCrudIT ----

    private String getVisitStatus(String token, UUID visitId) {
        ResponseEntity<String> response = get("/visits/" + visitId, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("status").asText();
    }

    private String getLeadStatus(String token, UUID leadId) {
        ResponseEntity<String> response = get("/leads/" + leadId, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).get("status").asText();
    }

    private List<JsonNode> notificationsOfType(String token, String type) {
        ResponseEntity<String> response = get("/notifications?unreadOnly=false", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = parse(response.getBody()).get("content");
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode notification : content) {
            if (notification.get("type").asText().equals(type)) {
                matches.add(notification);
            }
        }
        return matches;
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
                "admin-" + UUID.randomUUID() + "@scheduler.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label, Role role) {
        String email = label + "-" + UUID.randomUUID() + "@scheduler.test";
        String password = "employeepass1";
        Map<String, Object> body = Map.of(
                "fullName", "Test Employee " + label,
                "email", email,
                "password", password,
                "role", role.name());
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
