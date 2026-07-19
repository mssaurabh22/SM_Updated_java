package com.salesmanager.crm.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Proves that the Hibernate tenant filter + Postgres RLS actually isolate tenant data:
 * an admin from Org A can never see Org B's employees, whether listing or fetching by id.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void adminFromOrgA_cannotSeeOrgBsEmployees_inList() {
        AuthResponse orgAAuth = registerOrganization("Org A", "org-a-" + UUID.randomUUID(), "Alice Admin",
                "alice-" + UUID.randomUUID() + "@org-a.test", "password123");
        AuthResponse orgBAuth = registerOrganization("Org B", "org-b-" + UUID.randomUUID(), "Bob Admin",
                "bob-" + UUID.randomUUID() + "@org-b.test", "password123");

        JsonNode page = getEmployees(orgAAuth.accessToken());
        JsonNode content = page.get("content");

        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("id").asText()).isEqualTo(orgAAuth.employeeId().toString());

        for (JsonNode employee : content) {
            assertThat(employee.get("id").asText()).isNotEqualTo(orgBAuth.employeeId().toString());
            assertThat(employee.get("organizationId").asText()).isEqualTo(orgAAuth.orgId().toString());
        }
    }

    @Test
    void adminFromOrgA_getsNotFound_whenFetchingOrgBsEmployeeById() {
        AuthResponse orgAAuth = registerOrganization("Org A2", "org-a2-" + UUID.randomUUID(), "Alice Admin",
                "alice2-" + UUID.randomUUID() + "@org-a.test", "password123");
        AuthResponse orgBAuth = registerOrganization("Org B2", "org-b2-" + UUID.randomUUID(), "Bob Admin",
                "bob2-" + UUID.randomUUID() + "@org-b.test", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(orgAAuth.accessToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/employees/" + orgBAuth.employeeId(),
                HttpMethod.GET, entity, String.class);

        // Cross-tenant reads must be 404, never 403 - a 403 would leak that the resource exists.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminCanFetchTheirOwnEmployeeRecordById() {
        AuthResponse orgAAuth = registerOrganization("Org A3", "org-a3-" + UUID.randomUUID(), "Alice Admin",
                "alice3-" + UUID.randomUUID() + "@org-a.test", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(orgAAuth.accessToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/employees/" + orgAAuth.employeeId(),
                HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AuthResponse registerOrganization(String orgName, String subdomain, String adminFullName,
                                               String adminEmail, String adminPassword) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, subdomain, adminFullName, adminEmail, adminPassword);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private JsonNode getEmployees(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/employees", HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
