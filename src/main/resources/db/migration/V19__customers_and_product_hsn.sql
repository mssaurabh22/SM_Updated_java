-- Customer master (Quotations/Invoices plan, section 17.1): a real, reusable customer entity
-- distinct from Lead - today's invoicing module only ever copies fields off an optionally-linked
-- Lead, with nothing that supports a standalone "pick or quick-add a customer" flow. Org-wide,
-- unowned (no owner_id) - any entitled employee can find/quick-add a customer while quoting in
-- the field, same visibility shape as products/master_data rather than owner-scoped like leads.
CREATE TABLE customers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    name varchar(255) NOT NULL,
    contact_person varchar(255) NULL,
    phone varchar(20) NULL,
    email varchar(255) NULL,
    address varchar(1000) NULL,
    city_id uuid NULL REFERENCES master_data(id),
    state_id uuid NULL REFERENCES master_data(id),
    industry_id uuid NULL REFERENCES master_data(id),
    gstin varchar(20) NULL,
    notes varchar(2000) NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Same documented limitation as every other *_id column pointing into the shared master_data
-- table (see V3__leads.sql's comment) - the DB can't also enforce city_id -> CITY / state_id ->
-- STATE / industry_id -> INDUSTRY; that type check happens in the service layer via
-- MasterDataService#validateReference.
CREATE INDEX idx_customers_org_active ON customers (organization_id, is_active);

ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_customers ON customers
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON customers TO salesmanager_app;

-- HSN/SAC code, needed on Product so Quotation/Invoice line items can snapshot it - confirmed
-- absent from the original V11 inventory schema.
ALTER TABLE products ADD COLUMN hsn_sac_code varchar(20) NULL;
