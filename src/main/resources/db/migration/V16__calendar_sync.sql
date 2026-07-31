-- Section 15: live sync of an employee's own scheduled Visits into their personal Google
-- Calendar or Outlook (Microsoft Graph) calendar. One active connection per employee -
-- connecting a different provider replaces the row (see CalendarConnectionService), matching
-- real usage (a rep checks one calendar) and avoiding ambiguity about which provider new visits
-- sync to. access_token/refresh_token are AES-GCM encrypted at rest by TokenEncryptionService -
-- the first "encrypt sensitive data at rest" need in this codebase.
CREATE TABLE calendar_connections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    employee_id uuid NOT NULL REFERENCES employees(id),
    provider varchar(20) NOT NULL,
    access_token_encrypted varchar(4096) NOT NULL,
    refresh_token_encrypted varchar(4096) NOT NULL,
    token_expires_at timestamptz NOT NULL,
    calendar_id varchar(255) NOT NULL DEFAULT 'primary',
    connected_at timestamptz NOT NULL DEFAULT now(),
    last_sync_error varchar(500) NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (employee_id)
);

ALTER TABLE calendar_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_connections FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_calendar_connections ON calendar_connections
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON calendar_connections TO salesmanager_app;

-- externalCalendarEventId's presence is what tells CalendarSyncService "update" instead of
-- "create" on a later reschedule; calendar_sync_status/error surface the most recent attempt's
-- outcome (null = never attempted - no connection/entitlement at the time).
ALTER TABLE visits ADD COLUMN external_calendar_event_id varchar(255) NULL;
ALTER TABLE visits ADD COLUMN calendar_sync_status varchar(20) NULL;
ALTER TABLE visits ADD COLUMN calendar_sync_error varchar(500) NULL;
