-- Phase 2: Leads - the core sales-pipeline entity, plus its many-to-many product interest set.

CREATE TABLE leads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    company_name varchar(255) NOT NULL,
    industry_id uuid NULL REFERENCES master_data(id),
    business_type_id uuid NULL REFERENCES master_data(id),
    lead_source_id uuid NULL REFERENCES master_data(id),
    turnover numeric NULL,
    contact_person varchar(255) NOT NULL,
    designation_id uuid NULL REFERENCES master_data(id),
    contact_no varchar(10) NOT NULL,
    email varchar(255) NULL,
    city_id uuid NULL REFERENCES master_data(id),
    address text NULL,
    requirements text NULL,
    interest_level_id uuid NULL REFERENCES master_data(id),
    current_product_solution varchar(255) NULL,
    budget_range varchar(100) NULL,
    decision_maker_identified boolean NULL,
    objections text NULL,
    remarks text NULL,
    next_followup_date date NULL,
    expected_close_date date NULL,
    lost_reason_id uuid NULL REFERENCES master_data(id),
    status varchar(20) NOT NULL DEFAULT 'NEW' CHECK (status IN (
        'NEW', 'CONTACTED', 'NEGOTIATION', 'LOST', 'CLOSED_WON', 'LAPSED'
    )),
    owner_id uuid NOT NULL REFERENCES employees(id),
    created_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Same documented limitation as designation_id/city_id on employees in V2: each *_id column
-- above can only be enforced by the DB as "this id exists somewhere in master_data" - it
-- cannot cheaply also assert the referenced row's `type` matches the expected category
-- (industry_id -> INDUSTRY, business_type_id -> BUSINESS_TYPE, lead_source_id -> LEAD_SOURCE,
-- designation_id -> DESIGNATION, city_id -> CITY, interest_level_id -> INTEREST_LEVEL,
-- lost_reason_id -> LOST_REASON), since all 10 master-data categories share this one table.
-- That type check is done in the service layer via MasterDataService#validateReference.
CREATE INDEX idx_leads_org_owner ON leads (organization_id, owner_id);
CREATE INDEX idx_leads_org_status ON leads (organization_id, status);

-- Row-Level Security, identical pattern to tenant_isolation_employees/master_data.
ALTER TABLE leads ENABLE ROW LEVEL SECURITY;
ALTER TABLE leads FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_leads ON leads
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

-- Explicit grant here even though V1's ALTER DEFAULT PRIVILEGES already covers new tables -
-- each migration should be self-explanatory, same rationale as V2's identical comment.
GRANT SELECT, INSERT, UPDATE, DELETE ON leads TO salesmanager_app;

-- Lead's interest in multiple PRODUCT master-data rows, same shape as employee_products in
-- V2 (no organization_id column of its own - scoping flows transitively through leads.id,
-- and it is never queried independently of a specific lead - so no RLS policy here either,
-- matching employee_products' precedent).
CREATE TABLE lead_products (
    lead_id uuid NOT NULL REFERENCES leads(id),
    product_id uuid NOT NULL REFERENCES master_data(id),
    PRIMARY KEY (lead_id, product_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON lead_products TO salesmanager_app;
