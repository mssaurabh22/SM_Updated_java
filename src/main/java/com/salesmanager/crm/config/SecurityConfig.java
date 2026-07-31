package com.salesmanager.crm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.common.ErrorResponse;
import com.salesmanager.crm.security.JwtAuthFilter;
import com.salesmanager.crm.security.JwtTokenProvider;
import com.salesmanager.crm.security.TenantFilter;
import com.salesmanager.crm.security.TenantSessionManager;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // Comma-separated list; override via CORS_ALLOWED_ORIGINS for non-local frontend origins
    // (e.g. the CloudFront domain in Phase 7). Defaults to Vite's dev server port.
    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Platform-Key is the Platform Console's own auth header (InternalOrganizationController/
        // InternalEntitlementController) - without it explicitly allowed here, a real browser's CORS
        // preflight rejects it before the request is ever sent, surfacing as a bare "Network Error"
        // with no response at all. Every other client in this app (the normal tenant frontend, and
        // every curl/script call used for local testing) only ever sends Authorization, which is why
        // this gap went unnoticed until the Platform Console was actually exercised from a browser.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Platform-Key"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     JwtTokenProvider jwtTokenProvider,
                                                     TenantSessionManager tenantSessionManager,
                                                     PlatformTransactionManager transactionManager,
                                                     CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**").permitAll()
                        // Deliberately outside the normal JWT/TenantContext flow - see
                        // InternalEntitlementController's javadoc. Authorization for this path
                        // is instead a shared-secret X-Platform-Key header, checked inline in
                        // the controller itself, not by Spring Security.
                        .requestMatchers("/internal/**").permitAll()
                        // Google/Microsoft redirect the browser straight here after consent - a
                        // plain full-page navigation, so no Authorization header is (or can be)
                        // attached. Safe despite being unauthenticated: CalendarOAuthStateStore's
                        // single-use, short-lived state token (minted only during the earlier,
                        // normally-authenticated authorize-url call) is what recovers which
                        // employee this callback is for - see CalendarConnectionController.
                        .requestMatchers("/calendar-connections/*/callback").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                // Without explicit handlers, Spring Security's stateless default falls back to
                // Http403ForbiddenEntryPoint for EVERY rejection - including simply missing/invalid
                // credentials, which should be 401 (no/bad auth), reserving 403 for "authenticated
                // but not permitted" (e.g. an EMPLOYEE hitting an ADMIN-only route).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, request.getRequestURI(),
                                        "Authentication required"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpStatus.FORBIDDEN, request.getRequestURI(),
                                        "You do not have permission to perform this action")))
                .addFilterBefore(new JwtAuthFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantFilter(tenantSessionManager, transactionManager), JwtAuthFilter.class);

        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String path,
                             String message) throws java.io.IOException {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path)
                .fieldErrors(List.of())
                .build();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
