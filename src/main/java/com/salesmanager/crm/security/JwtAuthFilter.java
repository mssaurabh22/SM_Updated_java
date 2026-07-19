package com.salesmanager.crm.security;

import com.salesmanager.crm.employee.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code Authorization: Bearer <token>} header, validates the JWT, and - on
 * success - populates the SecurityContext with a {@link UserPrincipal}. Must run BEFORE
 * {@link TenantFilter} in the chain so the tenant filter can read the authenticated principal.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            try {
                Claims claims = jwtTokenProvider.parseClaims(token);
                if (jwtTokenProvider.isAccessToken(claims)) {
                    UUID employeeId = jwtTokenProvider.getEmployeeId(claims);
                    UUID orgId = jwtTokenProvider.getOrganizationId(claims);
                    Role role = jwtTokenProvider.getRole(claims);
                    String email = jwtTokenProvider.getEmail(claims);

                    UserPrincipal principal = new UserPrincipal(employeeId, orgId, role, email);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid/expired token: leave the SecurityContext unauthenticated.
                // Downstream authorization rules will reject the request as 401/403.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
