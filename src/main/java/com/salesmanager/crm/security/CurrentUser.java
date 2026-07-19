package com.salesmanager.crm.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Small, reusable helper for reading the current request's authenticated {@link UserPrincipal}
 * without repeating the SecurityContextHolder-cast boilerplate at every call site (e.g.
 * LeadService needs the current employee's id/role in several places: ownerId on create,
 * ownership checks on read/update, and the EMPLOYEE-vs-ADMIN visibility rule on list).
 *
 * Every caller of this class sits behind an endpoint that requires authentication (see
 * SecurityConfig's {@code anyRequest().authenticated()}), so a missing/wrong-typed principal
 * here indicates a filter-chain misconfiguration, not a normal request-time condition - hence
 * the unchecked IllegalStateException rather than a 401-mapped exception type.
 */
@Component
public class CurrentUser {

    public UserPrincipal get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalStateException("No authenticated UserPrincipal in SecurityContext");
        }
        return principal;
    }
}
