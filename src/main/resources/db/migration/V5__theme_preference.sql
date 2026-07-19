-- Phase 6: per-employee personal override of the org's theme branding
-- (organizations.theme_settings, added in V1). Same jsonb pattern as that column - see
-- Organization.themeSettings' javadoc and the cautionary tale in V4's notifications.payload
-- comment about why this must be jsonb (bound via @JdbcTypeCode(SqlTypes.JSON) on the Java
-- side), not text/varchar.
ALTER TABLE employees ADD COLUMN theme_preference jsonb NULL;
