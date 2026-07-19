-- Phase 0 initial schema: organizations, employees, refresh_tokens

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organizations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(255) NOT NULL,
    subdomain varchar(100) NOT NULL UNIQUE,
    theme_settings jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE employees (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    full_name varchar(255) NOT NULL,
    email varchar(255) NOT NULL,
    phone varchar(50),
    password_hash varchar(255) NOT NULL,
    role varchar(20) NOT NULL CHECK (role IN ('ADMIN', 'EMPLOYEE')),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_employees_org_email UNIQUE (organization_id, email)
);

CREATE INDEX idx_employees_organization_id ON employees (organization_id);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(id),
    token_hash varchar(255) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_employee_id ON refresh_tokens (employee_id);

-- Row-Level Security as defense-in-depth on top of the Hibernate application-level filter.
-- organizations has no organization_id column (it IS the tenant) - RLS not applicable there.
-- refresh_tokens is only ever looked up server-side by token hash + employee id, never
-- listed/filtered by organization directly in Phase 0, so RLS is skipped there for now.
ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees FORCE ROW LEVEL SECURITY;

-- app.bypass_rls is a narrow, explicitly-audited escape hatch set (via SET LOCAL, so it is
-- transaction-scoped and never leaks) only by AuthService's login flow, where the client
-- supplies only an email/password and the tenant is not yet known, so the employee lookup
-- must legitimately cross tenant boundaries. Every other code path relies solely on
-- app.current_org and never touches this flag.
--
-- NULLIF(..., '') guards against a Postgres GUC placeholder quirk: the first time a custom
-- (namespaced) parameter like app.current_org is SET on a connection, Postgres creates a
-- session-level placeholder for it; once the transaction that used SET LOCAL ends, the value
-- reverts to that placeholder's "reset value", which for on-the-fly custom GUCs is an EMPTY
-- STRING, not NULL. Since connections are pooled and reused across unrelated requests, a
-- later request that hasn't called activateTenant() yet (e.g. the start of login, before the
-- org is known) would otherwise hit "invalid input syntax for type uuid: ''" when this policy
-- is evaluated. NULLIF converts that empty string to NULL first, which casts to uuid cleanly.
CREATE POLICY tenant_isolation_employees ON employees
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

-- Postgres superusers ALWAYS bypass RLS, unconditionally - FORCE ROW LEVEL SECURITY has no
-- effect on them (FORCE only changes behavior for the table's OWNER; superuser bypass is a
-- separate, absolute rule). This migration itself runs as the privileged bootstrap role
-- (configured via spring.flyway.* datasource properties), but the application's own runtime
-- datasource (spring.datasource.*, used by Hibernate/JPA for every request) must connect as
-- a plain, non-superuser role for the RLS policy above to mean anything at all. Hence this
-- dedicated, minimally-privileged role, created here so no manual DB setup step is needed.
-- ${app_db_password} is a Flyway placeholder (see spring.flyway.placeholders.app_db_password
-- in application.yml), NOT a literal - it is substituted at migration time from the same
-- DB_APP_PASSWORD env var that spring.datasource.password reads, so the role is always
-- created with the exact password the app will actually connect with. Never hardcode this
-- role's password as a literal here again: that was a real gap (a env var override to
-- spring.datasource.password alone would silently NOT match what this role was created
-- with, since CREATE ROLE only runs once, on the very first migration of a fresh database).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'salesmanager_app') THEN
        CREATE ROLE salesmanager_app LOGIN PASSWORD '${app_db_password}' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO salesmanager_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO salesmanager_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO salesmanager_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO salesmanager_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO salesmanager_app;
