package com.salesmanager.crm.theme;

import jakarta.validation.constraints.Pattern;

/**
 * Shared shape for both org-level branding (Organization#themeSettings) and a personal
 * per-employee override (Employee#themePreference). All three fields are deliberately
 * optional - a partial override (e.g. only {@code mode} set) is valid and means "inherit
 * the other fields from whatever the next layer down provides". Deliberately NOT annotated
 * with {@code @JsonInclude(NON_NULL)}: this same type is both the HTTP response body (where
 * a genuinely-unset field must round-trip as an explicit JSON {@code null}, not be omitted -
 * see ThemeService#getMyThemePreference's javadoc) and the JSON stored in the jsonb columns,
 * so plain Jackson defaults (include nulls) keep both uses consistent.
 *
 * {@code mode}/{@code density} are plain Strings (not Java enums) rather than a JPA/Jackson
 * enum mapping, since this same record is reused for the org's fully-defaulted response and
 * the employee's genuinely-nullable one - ThemeService#validate is the single place that
 * enforces the allowed values (LIGHT/DARK, COMFORTABLE/COMPACT), returning the same
 * ErrorResponse.FieldErrorDetail shape as Bean Validation failures via InvalidThemeException.
 */
public record ThemeSettings(
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "primaryColor must be a hex color like #1565c0")
        String primaryColor,
        String mode,
        String density) {
}
