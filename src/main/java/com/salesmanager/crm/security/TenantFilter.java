package com.salesmanager.crm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs AFTER {@link JwtAuthFilter}. If the request is authenticated with a
 * {@link UserPrincipal}, sets {@link TenantContext} for the duration of the request and
 * enables the Hibernate/Postgres tenant scoping via {@link TenantSessionManager}. For
 * unauthenticated requests (e.g. the public auth endpoints), this filter is a no-op -
 * there is no tenant to scope to yet.
 *
 * For authenticated requests, the ENTIRE downstream chain (controller + repository calls)
 * is wrapped in a single Spring-managed transaction opened here. This is deliberate: Spring
 * Data JPA repository methods each open their own short-lived transaction by default, which
 * would begin AFTER this filter runs (filters execute before the transaction demarcation that
 * happens around the controller method), meaning a {@code SET LOCAL app.current_org} issued
 * here would apply to no transaction and silently do nothing. Opening the transaction here
 * means {@link TenantSessionManager#activateTenant} genuinely sets the Postgres session GUC
 * that RLS depends on, and any repository transactions started downstream simply join this
 * one (default REQUIRED propagation) rather than starting a separate one. This is also what
 * makes RLS a *real* backstop for entity-loading paths (e.g. find-by-id) that bypass the
 * Hibernate {@code @Filter} entirely - a known Hibernate limitation (filters only apply to
 * HQL/criteria queries and collection fetches, not primary-key lookups).
 *
 * The {@code finally} block is critical: it always clears {@link TenantContext} even if
 * the rest of the filter chain throws, since request-handling threads are pooled and would
 * otherwise leak tenant state into the next request handled by the same thread.
 */
public class TenantFilter extends OncePerRequestFilter {

    private final TenantSessionManager tenantSessionManager;
    private final PlatformTransactionManager transactionManager;

    public TenantFilter(TenantSessionManager tenantSessionManager, PlatformTransactionManager transactionManager) {
        this.tenantSessionManager = tenantSessionManager;
        this.transactionManager = transactionManager;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            // No tenant known yet (e.g. public auth endpoints) - nothing to activate.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.executeWithoutResult(status -> {
                tenantSessionManager.activateTenant(principal.getOrganizationId());
                try {
                    filterChain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new FilterChainExecutionException(e);
                }
            });
        } catch (FilterChainExecutionException e) {
            e.rethrow();
        } finally {
            tenantSessionManager.clearTenant();
        }
    }

    /** Lets checked exceptions from {@code filterChain.doFilter} survive the transaction callback. */
    private static final class FilterChainExecutionException extends RuntimeException {
        FilterChainExecutionException(Exception cause) {
            super(cause);
        }

        void rethrow() throws IOException, ServletException {
            Throwable cause = getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof ServletException se) {
                throw se;
            }
            throw new IllegalStateException(cause);
        }
    }
}
