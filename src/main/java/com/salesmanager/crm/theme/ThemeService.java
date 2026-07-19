package com.salesmanager.crm.theme;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.tenant.Organization;
import com.salesmanager.crm.tenant.OrganizationRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates org-level theme branding (Organization#themeSettings) and the personal,
 * per-employee override (Employee#themePreference). Both fields are raw JSON text bound as
 * jsonb (see the @JdbcTypeCode(SqlTypes.JSON) javadoc on each entity field); this service is
 * the only place that (de)serializes that text to/from the shared ThemeSettings DTO.
 *
 * The org-level getter always returns a fully-defaulted ThemeSettings (never a null field) -
 * it's the ultimate fallback layer for rendering, so ambiguity there would leak all the way
 * to the UI. The personal-preference getter is the opposite on purpose: it returns whatever
 * is genuinely stored, field by field, null where nothing was ever set, so the frontend can
 * merge org-default -> personal-override per field rather than all-or-nothing.
 */
@Service
public class ThemeService {

    static final String DEFAULT_PRIMARY_COLOR = "#1565c0";
    static final String DEFAULT_MODE = "LIGHT";
    static final String DEFAULT_DENSITY = "COMFORTABLE";

    private static final Set<String> VALID_MODES = Set.of("LIGHT", "DARK");
    private static final Set<String> VALID_DENSITIES = Set.of("COMFORTABLE", "COMPACT");

    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public ThemeService(OrganizationRepository organizationRepository,
                         EmployeeRepository employeeRepository,
                         CurrentUser currentUser,
                         ObjectMapper objectMapper) {
        this.organizationRepository = organizationRepository;
        this.employeeRepository = employeeRepository;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ThemeSettings getOrganizationTheme() {
        Organization organization = loadCurrentOrganization();
        ThemeSettings stored = parse(organization.getThemeSettings());
        return new ThemeSettings(
                stored.primaryColor() != null ? stored.primaryColor() : DEFAULT_PRIMARY_COLOR,
                stored.mode() != null ? stored.mode() : DEFAULT_MODE,
                stored.density() != null ? stored.density() : DEFAULT_DENSITY);
    }

    // noRollbackFor is essential, not cosmetic - see MasterDataService's identical comment:
    // TenantFilter wraps the whole request in one shared transaction, so an unmarked
    // RuntimeException here would poison that transaction even though GlobalExceptionHandler
    // translates it into a normal 400 response, causing an UnexpectedRollbackException to
    // escape uncaught once the response is already committed.
    @Transactional(noRollbackFor = InvalidThemeException.class)
    public ThemeSettings updateOrganizationTheme(ThemeSettings request) {
        validate(request);
        Organization organization = loadCurrentOrganization();
        ThemeSettings merged = merge(parse(organization.getThemeSettings()), request);
        organization.setThemeSettings(writeValueAsString(merged));
        organizationRepository.saveAndFlush(organization);
        return getOrganizationTheme();
    }

    @Transactional(readOnly = true)
    public ThemeSettings getMyThemePreference() {
        Employee employee = loadCurrentEmployee();
        return parse(employee.getThemePreference());
    }

    @Transactional(noRollbackFor = InvalidThemeException.class)
    public ThemeSettings updateMyThemePreference(ThemeSettings request) {
        validate(request);
        Employee employee = loadCurrentEmployee();
        ThemeSettings merged = merge(parse(employee.getThemePreference()), request);
        employee.setThemePreference(writeValueAsString(merged));
        employeeRepository.saveAndFlush(employee);
        return getMyThemePreference();
    }

    /**
     * Only mode/density need this manual check - primaryColor's hex-format check is already
     * enforced by @Pattern (via @Valid) at the controller before this method ever runs.
     */
    private void validate(ThemeSettings request) {
        if (request.mode() != null && !VALID_MODES.contains(request.mode())) {
            throw new InvalidThemeException("mode", "mode must be one of " + VALID_MODES);
        }
        if (request.density() != null && !VALID_DENSITIES.contains(request.density())) {
            throw new InvalidThemeException("density", "density must be one of " + VALID_DENSITIES);
        }
    }

    /** Partial update: any field left null on the incoming request keeps its existing value. */
    private ThemeSettings merge(ThemeSettings existing, ThemeSettings incoming) {
        return new ThemeSettings(
                incoming.primaryColor() != null ? incoming.primaryColor() : existing.primaryColor(),
                incoming.mode() != null ? incoming.mode() : existing.mode(),
                incoming.density() != null ? incoming.density() : existing.density());
    }

    private ThemeSettings parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new ThemeSettings(null, null, null);
        }
        try {
            return objectMapper.readValue(rawJson, ThemeSettings.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse stored theme settings JSON", e);
        }
    }

    private String writeValueAsString(ThemeSettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize theme settings", e);
        }
    }

    private Organization loadCurrentOrganization() {
        UUID organizationId = currentUser.get().getOrganizationId();
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));
    }

    private Employee loadCurrentEmployee() {
        UUID employeeId = currentUser.get().getEmployeeId();
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found: " + employeeId));
    }
}
