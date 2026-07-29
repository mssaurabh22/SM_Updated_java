-- Inventory module, Phase 1 (SalesManager-internal Inventory + Invoicing plan): a real Product
-- catalog with price/tax/stock, deliberately a brand-new table rather than an extension of the
-- generic master_data table (whose existing PRODUCT type is untouched, still used as-is by
-- Lead.productIds/Visit.productIds).

CREATE TABLE products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    sku varchar(100) NULL,
    name varchar(255) NOT NULL,
    description varchar(2000) NULL,
    unit_price numeric(12,2) NOT NULL DEFAULT 0,
    tax_rate_percent numeric(5,2) NOT NULL DEFAULT 0,
    unit_of_measure varchar(50) NULL,
    stock_quantity integer NOT NULL DEFAULT 0,
    low_stock_threshold integer NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_products_stock_quantity_non_negative CHECK (stock_quantity >= 0)
);

CREATE INDEX idx_products_org_active ON products (organization_id, is_active);

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_products ON products
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON products TO salesmanager_app;

-- Append-only stock ledger. products.stock_quantity is a MAINTAINED counter (updated in the
-- same transaction as inserting a row here), not a live SUM() at read time - correct here
-- (unlike employee_leave_balances, which must live-compute because leave_requests get edited/
-- cancelled/approved retroactively): this ledger is append-only, so the closer precedent is
-- activity_log's "denormalize at write time, trust it" discipline, not Leave's "never trust a
-- denormalized number" one. Nothing should ever update products.stock_quantity without also
-- inserting a matching row here in the same transaction - `SELECT product_id, SUM(quantity_change)
-- FROM stock_movements GROUP BY product_id` must always reconcile to products.stock_quantity.
CREATE TABLE stock_movements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    product_id uuid NOT NULL REFERENCES products(id),
    quantity_change integer NOT NULL,
    reason varchar(30) NOT NULL CHECK (reason IN ('MANUAL_ADJUSTMENT', 'INVOICE')),
    reference_id uuid NULL,
    note varchar(500) NULL,
    created_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_movements_product ON stock_movements (organization_id, product_id, created_at);

ALTER TABLE stock_movements ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_movements FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_stock_movements ON stock_movements
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON stock_movements TO salesmanager_app;
