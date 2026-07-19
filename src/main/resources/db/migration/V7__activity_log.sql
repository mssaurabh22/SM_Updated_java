-- Phase 5: Lead activity/journey timeline. A purpose-built activity_log table that key
-- Lead/Visit lifecycle moments write clean, human-readable entries to, exposed via one
-- flexible list endpoint (GET /activity) that serves both a single-Lead timeline and a
-- broader org-wide/personal activity feed.
--
-- owner_id and company_name are deliberately denormalized (captured at write time, not
-- joined live from leads) - they reflect who owned this lead / what it was called AT THE
-- TIME of this event, not necessarily its current values, and this keeps every query
-- (including the broad multi-lead feed, not just the single-lead timeline) a simple indexed
-- lookup with no join to leads needed.
CREATE TABLE activity_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    lead_id uuid NOT NULL REFERENCES leads(id),
    owner_id uuid NOT NULL REFERENCES employees(id),
    company_name varchar(255) NOT NULL,
    type varchar(30) NOT NULL,
    actor_id uuid NULL REFERENCES employees(id),
    description varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- ActivityLog extends TenantAware/BaseEntity, same as every other entity in this
    -- codebase, which always maps BOTH created_at and updated_at (@CreationTimestamp/
    -- @UpdateTimestamp) - Hibernate's schema validation fails at startup without this
    -- column even though an activity_log row is never updated post-insert in practice (same
    -- reasoning as notifications' identical migration comment).
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_activity_log_org_lead ON activity_log (organization_id, lead_id);
CREATE INDEX idx_activity_log_org_owner ON activity_log (organization_id, owner_id);

ALTER TABLE activity_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity_log FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_activity_log ON activity_log
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON activity_log TO salesmanager_app;
