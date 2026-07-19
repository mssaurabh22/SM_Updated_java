package com.salesmanager.crm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RefreshRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class AuthFlowIT extends AbstractIntegrationTest {

    @Test
    void registerOrganization_createsExactlyOneAdminEmployeeTiedToTheNewOrg() {
        String email = "admin-" + UUID.randomUUID() + "@acme.test";
        AuthResponse response = register("Acme Inc", "acme-" + UUID.randomUUID(), "Ada Admin", email, "supersecret1");

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(response.employeeId()).isNotNull();
        assertThat(response.orgId()).isNotNull();
    }

    @Test
    void login_withCorrectCredentials_issuesValidTokens() {
        String email = "login-ok-" + UUID.randomUUID() + "@acme.test";
        register("Acme Login Inc", "acme-login-" + UUID.randomUUID(), "Lou Login", email, "correct-password");

        LoginRequest loginRequest = new LoginRequest(email, "correct-password");
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/login", jsonEntity(loginRequest), AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
    }

    @Test
    void login_withWrongPassword_isRejected() {
        String email = "login-bad-" + UUID.randomUUID() + "@acme.test";
        register("Acme Bad Pw Inc", "acme-badpw-" + UUID.randomUUID(), "Lou Login", email, "correct-password");

        LoginRequest loginRequest = new LoginRequest(email, "totally-wrong-password");
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/auth/login", jsonEntity(loginRequest), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_rotatesToken_andRejectsTheOldOneAfterUse() {
        String email = "refresh-" + UUID.randomUUID() + "@acme.test";
        AuthResponse initial = register("Acme Refresh Inc", "acme-refresh-" + UUID.randomUUID(),
                "Ray Refresh", email, "supersecret1");

        RefreshRequest refreshRequest = new RefreshRequest(initial.refreshToken());
        ResponseEntity<AuthResponse> firstRefresh = restTemplate.postForEntity(
                baseUrl() + "/auth/refresh", jsonEntity(refreshRequest), AuthResponse.class);

        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRefresh.getBody()).isNotNull();
        assertThat(firstRefresh.getBody().refreshToken()).isNotEqualTo(initial.refreshToken());

        // Reusing the original (now-rotated-out) refresh token must be rejected.
        ResponseEntity<String> secondUseOfOldToken = restTemplate.postForEntity(
                baseUrl() + "/auth/refresh", jsonEntity(refreshRequest), String.class);
        assertThat(secondUseOfOldToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_revokesRefreshToken_soItCanNoLongerBeUsed() {
        String email = "logout-" + UUID.randomUUID() + "@acme.test";
        AuthResponse initial = register("Acme Logout Inc", "acme-logout-" + UUID.randomUUID(),
                "Lex Logout", email, "supersecret1");

        RefreshRequest refreshRequest = new RefreshRequest(initial.refreshToken());
        ResponseEntity<Void> logoutResponse = restTemplate.postForEntity(
                baseUrl() + "/auth/logout", jsonEntity(refreshRequest), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
                baseUrl() + "/auth/refresh", jsonEntity(refreshRequest), String.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private AuthResponse register(String orgName, String subdomain, String adminFullName,
                                   String adminEmail, String adminPassword) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, subdomain, adminFullName, adminEmail, adminPassword);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", jsonEntity(request), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
