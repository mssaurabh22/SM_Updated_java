-- Phase 3: Visits (field/telephonic interaction log against a Lead, with an auto-carried-
-- forward follow-up mechanism) and a minimal per-employee Notification inbox.

CREATE TABLE visits (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    lead_id uuid NOT NULL REFERENCES leads(id),
    visit_date date NOT NULL,
    scheduled_time time NULL,
    -- Unlike Industry/City/Product/etc., visit_type is a fixed structural concept (same
    -- category as leads.status/employees.role) rather than admin-customizable business
    -- data, so it's a plain CHECK-constrained column, not a master_data reference.
    visit_type varchar(20) NOT NULL CHECK (visit_type IN ('FIELD', 'TELEPHONIC')),
    purpose_id uuid NULL REFERENCES master_data(id),
    interest_level_id uuid NULL REFERENCES master_data(id),
    contact_person varchar(255) NULL,
    designation_id uuid NULL REFERENCES master_data(id),
    contact_no varchar(20) NULL,
    email varchar(255) NULL,
    city_id uuid NULL REFERENCES master_data(id),
    address text NULL,
    requirements text NULL,
    budget_range varchar(100) NULL,
    decision_maker_identified boolean NULL,
    objections text NULL,
    remarks text NULL,
    next_visit_date date NULL,
    status varchar(20) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'COMPLETED', 'MISSED')),
    created_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Same documented limitation as other *_id columns referencing the shared master_data
-- table (see V2/V3's identical comments): the FK only guarantees "this id exists
-- somewhere in master_data", not that its `type` matches (purpose_id -> VISIT_PURPOSE,
-- interest_level_id -> INTEREST_LEVEL, designation_id -> DESIGNATION, city_id -> CITY).
-- That type check is done in the service layer via MasterDataService#validateReference.
CREATE INDEX idx_visits_org_lead ON visits (organization_id, lead_id);
CREATE INDEX idx_visits_org_status ON visits (organization_id, status);

ALTER TABLE visits ENABLE ROW LEVEL SECURITY;
ALTER TABLE visits FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_visits ON visits
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON visits TO salesmanager_app;

-- Visit's interest in multiple PRODUCT master-data rows, same shape as lead_products/
-- employee_products - no organization_id column of its own, scoping flows transitively
-- through visits.id, so no RLS policy here either.
CREATE TABLE visit_products (
    visit_id uuid NOT NULL REFERENCES visits(id),
    product_id uuid NOT NULL REFERENCES master_data(id),
    PRIMARY KEY (visit_id, product_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON visit_products TO salesmanager_app;

-- Minimal per-employee notification inbox (Phase 3 only needs LEAD_REASSIGNED). payload is
-- a plain JSON string built by hand at the call site (e.g. {"leadId":"...","companyName":"..."})
-- and mapped as a plain Java String, not a structured jsonb type - no generic object-mapping
-- infrastructure for this one use case. Column is `text`, not `jsonb`: Hibernate binds a plain
-- String parameter as varchar, which Postgres will NOT implicitly cast to jsonb (a real bug
-- caught during Phase 3 verification - every notification insert failed with "column payload
-- is of type jsonb but expression is of type character varying", which in turn poisoned the
-- request's shared transaction and corrupted a pooled connection for whatever request reused
-- it next). `text` matches the actual Java mapping exactly; switch to a real jsonb column with
-- a Hibernate JSON type/converter only if this ever needs structured querying.
CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    recipient_id uuid NOT NULL REFERENCES employees(id),
    type varchar(50) NOT NULL,
    payload text NULL,
    is_read boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Notification extends TenantAware/BaseEntity, same as every other entity in this
    -- codebase, which always maps BOTH created_at and updated_at (@CreationTimestamp/
    -- @UpdateTimestamp) - Hibernate's schema validation fails at startup without this
    -- column even though a Notification is never partially "updated" in practice.
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_org_recipient_read ON notifications (organization_id, recipient_id, is_read);

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_notifications ON notifications
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON notifications TO salesmanager_app;
