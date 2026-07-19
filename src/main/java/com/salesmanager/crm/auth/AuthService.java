package com.salesmanager.crm.auth;

import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RefreshRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.masterdata.MasterDataSeedService;
import com.salesmanager.crm.security.JwtTokenProvider;
import com.salesmanager.crm.security.TenantSessionManager;
import com.salesmanager.crm.tenant.Organization;
import com.salesmanager.crm.tenant.OrganizationRepository;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates registration, login, refresh-token rotation, and logout. Controllers stay
 * thin; all tenant-context/RLS wiring for the auth flows lives here since these are the
 * few code paths that run before (or without) a JWT-derived {@code TenantContext}.
 */
@Service
public class AuthService {

    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantSessionManager tenantSessionManager;
    private final MasterDataSeedService masterDataSeedService;

    public AuthService(OrganizationRepository organizationRepository,
                        EmployeeRepository employeeRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        JwtTokenProvider jwtTokenProvider,
                        TenantSessionManager tenantSessionManager,
                        MasterDataSeedService masterDataSeedService) {
        this.organizationRepository = organizationRepository;
        this.employeeRepository = employeeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tenantSessionManager = tenantSessionManager;
        this.masterDataSeedService = masterDataSeedService;
    }

    @Transactional
    public AuthResponse registerOrganization(RegisterOrganizationRequest request) {
        Organization organization = Organization.builder()
                .name(request.organizationName())
                .subdomain(request.subdomain())
                .build();
        organization = organizationRepository.save(organization);

        Employee admin;
        try {
            // The new org's id is already known here, so we activate tenant context
            // directly (no cross-tenant bypass needed) - this both drives the
            // Hibernate tenantFilter and satisfies the RLS WITH CHECK on insert.
            tenantSessionManager.activateTenant(organization.getId());

            // Seed a starter set of dropdown-driving master data for the new org before/
            // alongside creating the first admin - so the admin never sees empty dropdowns.
            masterDataSeedService.seedDefaults();

            admin = Employee.builder()
                    .fullName(request.adminFullName())
                    .email(request.adminEmail())
                    .passwordHash(passwordEncoder.encode(request.adminPassword()))
                    .role(Role.ADMIN)
                    .active(true)
                    .build();
            admin = employeeRepository.save(admin);
        } finally {
            tenantSessionManager.clearTenant();
        }

        return issueTokens(admin);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Employee employee;
        try {
            // Tenant is not yet known (client supplies only email/password), so we must
            // look up across all tenants. This is the one deliberate, documented RLS
            // bypass in the system - see TenantSessionManager#bypassRlsForCrossTenantLookup.
            tenantSessionManager.bypassRlsForCrossTenantLookup();
            employee = employeeRepository.findByEmail(request.email())
                    .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        } finally {
            tenantSessionManager.endBypass();
        }

        if (!passwordEncoder.matches(request.password(), employee.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!employee.isActive()) {
            throw new BadCredentialsException("Account is disabled");
        }

        try {
            tenantSessionManager.activateTenant(employee.getOrganizationId());
            return issueTokens(employee);
        } finally {
            tenantSessionManager.clearTenant();
        }
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String rawToken = request.refreshToken();
        if (!jwtTokenProvider.isValid(rawToken)) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }
        Claims claims = jwtTokenProvider.parseClaims(rawToken);
        if (!jwtTokenProvider.isRefreshToken(claims)) {
            throw new BadCredentialsException("Token is not a refresh token");
        }

        UUID employeeId = jwtTokenProvider.getEmployeeId(claims);
        UUID orgId = jwtTokenProvider.getOrganizationId(claims);
        String tokenHash = hash(rawToken);

        try {
            // orgId comes from a claim inside a token we signed ourselves - it cannot have
            // been tampered with by the client, so it's safe to trust for tenant activation.
            tenantSessionManager.activateTenant(orgId);

            RefreshToken existing = refreshTokenRepository.findByTokenHashAndEmployeeId(tokenHash, employeeId)
                    .orElseThrow(() -> new BadCredentialsException("Refresh token not recognized"));
            if (existing.isRevoked()) {
                throw new BadCredentialsException("Refresh token has already been used");
            }
            if (existing.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                throw new BadCredentialsException("Refresh token has expired");
            }

            Employee employee = employeeRepository.findById(employeeId)
                    .orElseThrow(() -> new BadCredentialsException("Employee no longer exists"));
            if (!employee.isActive()) {
                throw new BadCredentialsException("Account is disabled");
            }

            // Rotate: revoke the used token and issue a brand new pair.
            existing.setRevoked(true);
            refreshTokenRepository.save(existing);

            return issueTokens(employee);
        } finally {
            tenantSessionManager.clearTenant();
        }
    }

    @Transactional
    public void logout(RefreshRequest request) {
        String rawToken = request.refreshToken();
        if (!jwtTokenProvider.isValid(rawToken) || !jwtTokenProvider.isRefreshToken(jwtTokenProvider.parseClaims(rawToken))) {
            // Logout is idempotent - an already-invalid token requires no action.
            return;
        }
        Claims claims = jwtTokenProvider.parseClaims(rawToken);
        UUID employeeId = jwtTokenProvider.getEmployeeId(claims);
        String tokenHash = hash(rawToken);

        refreshTokenRepository.findByTokenHashAndEmployeeId(tokenHash, employeeId)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    private AuthResponse issueTokens(Employee employee) {
        String accessToken = jwtTokenProvider.generateAccessToken(
                employee.getId(), employee.getOrganizationId(), employee.getRole(), employee.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                employee.getId(), employee.getOrganizationId(), employee.getRole(), employee.getEmail());

        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .employeeId(employee.getId())
                .tokenHash(hash(refreshToken))
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                        .plus(java.time.Duration.ofMillis(jwtTokenProvider.getRefreshExpiryMs())))
                .revoked(false)
                .build();
        refreshTokenRepository.save(refreshTokenEntity);

        return new AuthResponse(accessToken, refreshToken, employee.getId(), employee.getOrganizationId(), employee.getRole());
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
