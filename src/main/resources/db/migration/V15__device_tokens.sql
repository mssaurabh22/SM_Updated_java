-- Backs Firebase Cloud Messaging push delivery (notification.DeviceToken) - an employee can
-- register several tokens (one per browser/device); platform already covers ANDROID/IOS so no
-- migration is needed once a future Flutter app starts registering tokens too.
CREATE TABLE device_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    employee_id uuid NOT NULL REFERENCES employees(id),
    token varchar(4096) NOT NULL,
    platform varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (token)
);

CREATE INDEX idx_device_tokens_employee ON device_tokens (organization_id, employee_id);

ALTER TABLE device_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_tokens FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_device_tokens ON device_tokens
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON device_tokens TO salesmanager_app;
