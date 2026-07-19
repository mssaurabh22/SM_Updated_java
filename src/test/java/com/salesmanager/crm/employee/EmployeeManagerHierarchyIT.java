package com.salesmanager.crm.employee;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.security.TenantSessionManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
 * Part B (Employee Entitlement plan) adds Employee.managerId - a self-referential, nullable,
 * raw-id field (see Employee's javadoc). Covers: a valid same-org managerId round-tripping,
 * self-reference rejection, cycle rejection (walking the managerId chain), a
 * nonexistent/cross-tenant managerId being rejected as not-found - same "cross-tenant id
 * indistinguishable from nonexistent" idiom as every other reference in this codebase
 * (EmployeeService#validateManager relies on the same Hibernate filter/RLS backstop as
 * EmployeeRepository#findById everywhere else) - and plan B.2's recursive visibility scoping
 * (EmployeeHierarchyService#getAllSubordinateIds), exercised directly against the service bean
 * under a manually-activated tenant transaction (same TenantSessionManager/TransactionTemplate
 * pattern as SchedulerIT/ReportingIT), including a spot-check that the underlying recursive CTE
 * genuinely respects Postgres RLS across tenants.
 */
class EmployeeManagerHierarchyIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EmployeeHierarchyService employeeHierarchyService;

    @Autowired
    private TenantSessionManager tenantSessionManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void create_withManagerIdReferencingAnotherEmployeeInSameOrg_succeeds() {
        AuthResponse admin = registerOrganization("Manager Valid Org");
        String managerId = createEmployee(admin.accessToken(), "Manager One", null);

        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Direct Report");
        body.put("email", "report-" + UUID.randomUUID() + "@managerhierarchy.test");
        body.put("password", "supersecret1");
        body.put("role", Role.EMPLOYEE.name());
        body.put("managerId", managerId);

        ResponseEntity<String> response = post("/employees", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = parse(response.getBody());
        assertThat(created.get("managerId").asText()).isEqualTo(managerId);
    }

    @Test
    void create_withManagerIdEqualToSelf_isRejected() {
        // Self-reference on create can only be attempted via update (the new employee has no id
        // yet at create time) - so this proves the same rule on update instead, which is where
        // it actually matters in practice.
        AuthResponse admin = registerOrganization("Manager Self Org");
        String employeeId = createEmployee(admin.accessToken(), "Solo Employee", null);

        Map<String, Object> updateBody = Map.of("managerId", employeeId);
        ResponseEntity<String> response = put("/employees/" + employeeId, admin.accessToken(), updateBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = parse(response.getBody());
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("managerId");
    }

    @Test
    void update_withManagerIdThatWouldCreateACycle_isRejected() {
        AuthResponse admin = registerOrganization("Manager Cycle Org");
        String xId = createEmployee(admin.accessToken(), "Employee X", null);
        String yId = createEmployee(admin.accessToken(), "Employee Y", xId);
        // Y reports to X. Now try to make X report to Y - a direct 2-cycle: X -> Y -> X.
        Map<String, Object> updateBody = Map.of("managerId", yId);
        ResponseEntity<String> response = put("/employees/" + xId, admin.accessToken(), updateBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_withNonExistentManagerId_isRejectedNotFound() {
        AuthResponse admin = registerOrganization("Manager Missing Org");
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Orphan Report");
        body.put("email", "orphan-" + UUID.randomUUID() + "@managerhierarchy.test");
        body.put("password", "supersecret1");
        body.put("role", Role.EMPLOYEE.name());
        body.put("managerId", UUID.randomUUID().toString());

        ResponseEntity<String> response = post("/employees", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_withManagerIdBelongingToAnotherOrg_isRejectedNotFound() {
        AuthResponse orgA = registerOrganization("Manager CrossTenant Org A");
        AuthResponse orgB = registerOrganization("Manager CrossTenant Org B");

        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Cross Tenant Report");
        body.put("email", "crosstenant-" + UUID.randomUUID() + "@managerhierarchy.test");
        body.put("password", "supersecret1");
        body.put("role", Role.EMPLOYEE.name());
        body.put("managerId", orgB.employeeId().toString());

        ResponseEntity<String> response = post("/employees", orgA.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAllSubordinateIds_resolvesMultiLevelChain_recursively_notUpward() {
        AuthResponse admin = registerOrganization("Hierarchy Recursive Org");
        String aId = createEmployee(admin.accessToken(), "Manager A", null);
        String bId = createEmployee(admin.accessToken(), "Team Lead B", aId);
        String cId = createEmployee(admin.accessToken(), "Member C", bId);

        // A -> {B, C}: both the direct report (B) and the indirect one (C, via B) - the
        // recursive part of the CTE, not just a flat "WHERE manager_id = me".
        assertThat(getSubordinates(admin.orgId(), UUID.fromString(aId)))
                .containsExactlyInAnyOrder(UUID.fromString(bId), UUID.fromString(cId));

        // B -> {C} only - NOT A, since A is above B in the chain, not below it.
        assertThat(getSubordinates(admin.orgId(), UUID.fromString(bId)))
                .containsExactly(UUID.fromString(cId));

        // C is a leaf - no reports of their own, correctly empty (not an error).
        assertThat(getSubordinates(admin.orgId(), UUID.fromString(cId))).isEmpty();
    }

    @Test
    void getAllSubordinateIds_crossTenantManagerId_neverLeaksSubordinatesAcrossOrgs() {
        AuthResponse orgA = registerOrganization("Hierarchy Isolation Org A");
        AuthResponse orgB = registerOrganization("Hierarchy Isolation Org B");
        String orgAManagerId = createEmployee(orgA.accessToken(), "Org A Manager", null);
        createEmployee(orgA.accessToken(), "Org A Report", orgAManagerId);

        // Querying Org A's manager id while Org B's tenant context is the one active proves the
        // recursive CTE's RLS scoping directly: even though orgAManagerId is a real id with real
        // subordinates under Org A's own tenant context, resolving it under Org B's session must
        // come back empty - Postgres RLS (app.current_org), not the Hibernate tenantFilter
        // (which native queries bypass), is what's actually preventing the leak here.
        assertThat(getSubordinates(orgB.orgId(), UUID.fromString(orgAManagerId))).isEmpty();

        // Sanity check: the same lookup, run back under Org A's own tenant context, does resolve
        // - confirming the empty result above is RLS scoping, not a query that's simply broken.
        assertThat(getSubordinates(orgA.orgId(), UUID.fromString(orgAManagerId))).isNotEmpty();
    }

    private Set<UUID> getSubordinates(UUID organizationId, UUID employeeId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            try {
                tenantSessionManager.activateTenant(organizationId);
                return employeeHierarchyService.getAllSubordinateIds(employeeId);
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    // ---- helpers ----

    private String createEmployee(String adminToken, String fullName, String managerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("email", "emp-" + UUID.randomUUID() + "@managerhierarchy.test");
        body.put("password", "supersecret1");
        body.put("role", Role.EMPLOYEE.name());
        if (managerId != null) {
            body.put("managerId", managerId);
        }
        ResponseEntity<String> response = post("/employees", adminToken, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
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
                "admin-" + UUID.randomUUID() + "@managerhierarchy.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
