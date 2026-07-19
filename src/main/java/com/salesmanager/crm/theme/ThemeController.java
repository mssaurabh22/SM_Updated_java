package com.salesmanager.crm.theme;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller over ThemeService, matching the layering used by MasterDataController/
 * EmployeeController. Org-branding GET is open to any authenticated user (every employee
 * needs to render the org's branding, not just ADMIN); its PUT is ADMIN-only. Both
 * personal-preference endpoints are open to any authenticated user - CurrentUser (used
 * inside ThemeService) always resolves to the caller's own employee id, so there's no id
 * path variable to guard here.
 */
@RestController
public class ThemeController {

    private final ThemeService themeService;

    public ThemeController(ThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping("/organizations/me/theme")
    public ThemeSettings getOrganizationTheme() {
        return themeService.getOrganizationTheme();
    }

    @PutMapping("/organizations/me/theme")
    @PreAuthorize("hasRole('ADMIN')")
    public ThemeSettings updateOrganizationTheme(@Valid @RequestBody ThemeSettings request) {
        return themeService.updateOrganizationTheme(request);
    }

    @GetMapping("/employees/me/theme-preference")
    public ThemeSettings getMyThemePreference() {
        return themeService.getMyThemePreference();
    }

    @PutMapping("/employees/me/theme-preference")
    public ThemeSettings updateMyThemePreference(@Valid @RequestBody ThemeSettings request) {
        return themeService.updateMyThemePreference(request);
    }
}
