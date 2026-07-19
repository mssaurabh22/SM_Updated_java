package com.salesmanager.crm.security;

import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.Role;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal backed by an authenticated Employee's JWT claims.
 * Deliberately does NOT hold a live JPA entity reference - it's built directly
 * from validated token claims so it works even without touching the DB per-request.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final UUID employeeId;
    private final UUID organizationId;
    private final Role role;
    private final String email;

    public UserPrincipal(UUID employeeId, UUID organizationId, Role role, String email) {
        this.employeeId = employeeId;
        this.organizationId = organizationId;
        this.role = role;
        this.email = email;
    }

    public static UserPrincipal of(Employee employee) {
        return new UserPrincipal(employee.getId(), employee.getOrganizationId(), employee.getRole(), employee.getEmail());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
