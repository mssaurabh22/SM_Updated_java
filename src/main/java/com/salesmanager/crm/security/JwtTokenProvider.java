package com.salesmanager.crm.security;

import com.salesmanager.crm.employee.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues and validates JWT access/refresh tokens. Claims embedded: sub (employee id),
 * org_id, role, email. Token type ("access"/"refresh") is also embedded so a refresh
 * token can't be replayed as an access token and vice versa.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_ORG_ID = "org_id";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey signingKey;
    private final long accessExpiryMs;
    private final long refreshExpiryMs;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                             @Value("${jwt.access-expiry-ms}") long accessExpiryMs,
                             @Value("${jwt.refresh-expiry-ms}") long refreshExpiryMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiryMs = accessExpiryMs;
        this.refreshExpiryMs = refreshExpiryMs;
    }

    public String generateAccessToken(UUID employeeId, UUID organizationId, Role role, String email) {
        return buildToken(employeeId, organizationId, role, email, TYPE_ACCESS, accessExpiryMs);
    }

    public String generateRefreshToken(UUID employeeId, UUID organizationId, Role role, String email) {
        return buildToken(employeeId, organizationId, role, email, TYPE_REFRESH, refreshExpiryMs);
    }

    public long getRefreshExpiryMs() {
        return refreshExpiryMs;
    }

    private String buildToken(UUID employeeId, UUID organizationId, Role role, String email,
                               String type, long expiryMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiryMs);
        return Jwts.builder()
                .subject(employeeId.toString())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_ORG_ID, organizationId.toString())
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /** Parses and validates signature/expiry; throws JwtException on any failure. */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public UUID getEmployeeId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public UUID getOrganizationId(Claims claims) {
        return UUID.fromString(claims.get(CLAIM_ORG_ID, String.class));
    }

    public Role getRole(Claims claims) {
        return Role.valueOf(claims.get(CLAIM_ROLE, String.class));
    }

    public String getEmail(Claims claims) {
        return claims.get(CLAIM_EMAIL, String.class);
    }

    /** Returns true if the token is well-formed, signature-valid, and not expired. */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }
}
