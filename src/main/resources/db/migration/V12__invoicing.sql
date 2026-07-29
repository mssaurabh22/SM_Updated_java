-- Invoicing module, Phase 2 (SalesManager-internal Inventory + Invoicing plan). Invoice is
-- create-only in v1: no edit/delete/void endpoint exists, so there is deliberately no
-- compensating "reverse the stock deduction" logic anywhere in this module yet.

-- Keyed by (organization_id, year) so "INV-2026-0001" resets to 0001 every January instead of
-- drifting - year is the invoice_date's year, allocated via a plain SELECT...FOR UPDATE row
-- lock inside the request's own transaction (NOT the AdvisoryLockRunner scheduled-job
-- mechanism - that guards whole job executions across instances, not a per-row counter here).
-- No RLS - an internal counter never read by tenant-facing queries directly, same reasoning
-- V1 gave for skipping RLS on refresh_tokens.
CREATE TABLE invoice_number_counters (
    organization_id uuid NOT NULL REFERENCES organizations(id),
    year integer NOT NULL,
    next_number integer NOT NULL DEFAULT 1,
    PRIMARY KEY (organization_id, year)
);

-- owner_id/created_by mirror Lead's exact two-column shape (see Lead.java's javadoc): owner_id
-- is who it's visible to under the EMPLOYEE-role visibility rule (and TEAM_VISIBILITY's
-- expanded manager scope), created_by is pure audit. Always equal in v1 since there is no
-- invoice-reassignment endpoint, but kept as two columns for consistency with Lead's convention
-- and so a future reassignment feature needs no schema change.
CREATE TABLE invoices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_number varchar(50) NOT NULL,
    lead_id uuid NULL REFERENCES leads(id),
    owner_id uuid NOT NULL REFERENCES employees(id),
    created_by uuid NOT NULL REFERENCES employees(id),
    customer_name varchar(255) NOT NULL,
    customer_contact_person varchar(255) NULL,
    customer_phone varchar(20) NULL,
    customer_email varchar(255) NULL,
    customer_address varchar(1000) NULL,
    customer_gstin varchar(20) NULL,
    invoice_date date NOT NULL,
    subtotal numeric(12,2) NOT NULL,
    tax_total numeric(12,2) NOT NULL,
    grand_total numeric(12,2) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'UNPAID' CHECK (status IN ('UNPAID', 'PAID')),
    notes varchar(2000) NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, invoice_number)
);

CREATE INDEX idx_invoices_org_owner ON invoices (organization_id, owner_id);

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_invoices ON invoices
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON invoices TO salesmanager_app;

-- product_id nullable - set only for a catalog line; description/unit_price/tax_rate_percent
-- are always snapshotted at invoice time (never re-read from the live Product afterwards), so
-- an invoice's PDF/history never silently changes if the Product's price/name changes later.
CREATE TABLE invoice_line_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL REFERENCES invoices(id),
    product_id uuid NULL REFERENCES products(id),
    description varchar(500) NOT NULL,
    quantity numeric(10,2) NOT NULL CHECK (quantity > 0),
    unit_price numeric(12,2) NOT NULL,
    tax_rate_percent numeric(5,2) NOT NULL DEFAULT 0,
    line_subtotal numeric(12,2) NOT NULL,
    line_tax_amount numeric(12,2) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_invoice_line_items_invoice ON invoice_line_items (organization_id, invoice_id);

ALTER TABLE invoice_line_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_line_items FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_invoice_line_items ON invoice_line_items
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON invoice_line_items TO salesmanager_app;

GRANT SELECT, INSERT, UPDATE ON invoice_number_counters TO salesmanager_app;
