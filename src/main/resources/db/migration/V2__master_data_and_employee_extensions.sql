-- Phase 1: generic master-data (dropdown-driving reference tables) + Employee extensions.

CREATE TABLE master_data (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    type varchar(30) NOT NULL CHECK (type IN (
        'INDUSTRY', 'CITY', 'PRODUCT', 'BUSINESS_TYPE', 'DESIGNATION', 'VISIT_PURPOSE',
        'NEXT_ACTION', 'LOST_REASON', 'INTEREST_LEVEL', 'LEAD_SOURCE'
    )),
    code varchar(100) NOT NULL,
    label varchar(255) NOT NULL,
    sort_order int NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    parent_id uuid NULL REFERENCES master_data(id),
    metadata jsonb NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_master_data_org_type_code UNIQUE (organization_id, type, code)
);

CREATE INDEX idx_master_data_org_type ON master_data (organization_id, type);

-- Row-Level Security, identical pattern to tenant_isolation_employees in V1 - reuses the
-- same app.current_org / app.bypass_rls GUCs (see the extensive comment in V1 for the
-- NULLIF(..., '') rationale and the bypass flag's narrow, audited purpose).
ALTER TABLE master_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE master_data FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_master_data ON master_data
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

-- Explicit grant here even though V1's ALTER DEFAULT PRIVILEGES already covers new tables -
-- each migration should be self-explanatory and not depend on a reader tracing back to a
-- different file to understand why the app role can touch this table.
GRANT SELECT, INSERT, UPDATE, DELETE ON master_data TO salesmanager_app;

-- Employee extensions: designation/city are references into the single shared master_data
-- table, so the FK can only guarantee "this id exists in master_data" - it cannot cheaply
-- enforce that the referenced row's `type` column is actually 'DESIGNATION'/'CITY' (that
-- would require a partial/conditional FK, which Postgres doesn't support directly). That
-- type check is therefore done in the service layer (see MasterDataService#validateReference),
-- not the DB schema.
ALTER TABLE employees ADD COLUMN designation_id uuid NULL REFERENCES master_data(id);
ALTER TABLE employees ADD COLUMN city_id uuid NULL REFERENCES master_data(id);

CREATE TABLE employee_products (
    employee_id uuid NOT NULL REFERENCES employees(id),
    product_id uuid NOT NULL REFERENCES master_data(id),
    PRIMARY KEY (employee_id, product_id)
);
