-- Quotation module (Quotations/Invoices plan, section 17.2): a real Draft/Sent/Approved/
-- Rejected/Converted workflow, built fresh rather than repurposing the existing invoicing
-- module (which becomes the genuine post-sale Invoice in V21 instead - see that migration).

CREATE TABLE quotation_number_counters (
    organization_id uuid NOT NULL REFERENCES organizations(id),
    year integer NOT NULL,
    next_number integer NOT NULL DEFAULT 1,
    PRIMARY KEY (organization_id, year)
);

-- No RLS - an internal counter never read by tenant-facing queries directly, same reasoning
-- V12's invoice_number_counters (and V1's refresh_tokens) gave.
GRANT SELECT, INSERT, UPDATE ON quotation_number_counters TO salesmanager_app;

CREATE TABLE quotations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    quotation_number varchar(50) NOT NULL,
    lead_id uuid NULL REFERENCES leads(id),
    customer_id uuid NOT NULL REFERENCES customers(id),
    -- customer_* below are SNAPSHOTTED from Customer at creation time (same "keeps its own copy"
    -- discipline as invoices.customer_* / activity_log's denormalization) - editing the Customer
    -- master afterward never retroactively changes an already-created quotation.
    customer_name varchar(255) NOT NULL,
    customer_contact_person varchar(255) NULL,
    customer_designation varchar(255) NULL,
    customer_phone varchar(20) NULL,
    customer_email varchar(255) NULL,
    customer_billing_address varchar(1000) NULL,
    customer_gstin varchar(20) NULL,
    industry_id uuid NULL REFERENCES master_data(id),
    industry_other varchar(255) NULL,
    city_id uuid NULL REFERENCES master_data(id),
    city_other varchar(255) NULL,
    state_id uuid NULL REFERENCES master_data(id),
    state_other varchar(255) NULL,
    interest_level_id uuid NULL REFERENCES master_data(id),
    interest_level_other varchar(255) NULL,
    business_type_id uuid NULL REFERENCES master_data(id),
    business_type_other varchar(255) NULL,
    owner_id uuid NOT NULL REFERENCES employees(id),
    type_of_visit varchar(20) NULL CHECK (type_of_visit IN ('FIELD', 'TELEPHONIC')),
    quotation_date date NOT NULL,
    valid_till_date date NULL,
    reference_enquiry_no varchar(100) NULL,
    expected_close_date date NULL,
    quotation_notes text NULL,
    terms_and_conditions text NULL,
    internal_note text NULL,
    follow_up_date date NULL,
    follow_up_time time NULL,
    follow_up_by_employee_id uuid NULL REFERENCES employees(id),
    follow_up_note varchar(1000) NULL,
    subtotal numeric(12,2) NOT NULL,
    discount_total numeric(12,2) NOT NULL DEFAULT 0,
    taxable_amount numeric(12,2) NOT NULL,
    cgst_total numeric(12,2) NOT NULL DEFAULT 0,
    sgst_total numeric(12,2) NOT NULL DEFAULT 0,
    grand_total numeric(12,2) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT', 'SENT', 'APPROVED', 'REJECTED', 'CONVERTED'
    )),
    converted_invoice_id uuid NULL REFERENCES invoices(id),
    created_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, quotation_number)
);

CREATE INDEX idx_quotations_org_owner ON quotations (organization_id, owner_id);
CREATE INDEX idx_quotations_org_status ON quotations (organization_id, status);

ALTER TABLE quotations ENABLE ROW LEVEL SECURITY;
ALTER TABLE quotations FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_quotations ON quotations
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON quotations TO salesmanager_app;

-- product_id nullable - set only for a catalog line (mirrors invoice_line_items exactly), plus
-- hsn_sac (snapshotted from Product, or typed for an ad-hoc line) and discount_percent (absent
-- from invoice_line_items until V21 retrofits it - see that migration). CGST/SGST are computed
-- as tax_rate_percent / 2 each at write time and stored (not recomputed at read time) so a
-- historical quotation's PDF never drifts if this splitting rule is ever revisited.
CREATE TABLE quotation_line_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    quotation_id uuid NOT NULL REFERENCES quotations(id),
    product_id uuid NULL REFERENCES products(id),
    hsn_sac varchar(20) NULL,
    description varchar(500) NOT NULL,
    quantity numeric(10,2) NOT NULL CHECK (quantity > 0),
    unit varchar(50) NULL,
    unit_price numeric(12,2) NOT NULL,
    discount_percent numeric(5,2) NOT NULL DEFAULT 0,
    tax_rate_percent numeric(5,2) NOT NULL DEFAULT 0,
    line_subtotal numeric(12,2) NOT NULL,
    line_discount_amount numeric(12,2) NOT NULL DEFAULT 0,
    line_taxable_amount numeric(12,2) NOT NULL,
    line_cgst_amount numeric(12,2) NOT NULL DEFAULT 0,
    line_sgst_amount numeric(12,2) NOT NULL DEFAULT 0,
    line_total numeric(12,2) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_quotation_line_items_quotation ON quotation_line_items (organization_id, quotation_id);

ALTER TABLE quotation_line_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE quotation_line_items FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_quotation_line_items ON quotation_line_items
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON quotation_line_items TO salesmanager_app;

-- Reuses the existing AttachmentStorageService/LocalFilesystemAttachmentStorageService bean from
-- V18's lead-attachments work (dependency-injected, not duplicated) - new table/entity/service
-- only, no changes to lead_attachments itself.
CREATE TABLE quotation_attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    quotation_id uuid NOT NULL REFERENCES quotations(id),
    file_name varchar(255) NOT NULL,
    storage_key varchar(500) NOT NULL,
    content_type varchar(100) NOT NULL,
    file_size bigint NOT NULL,
    uploaded_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_quotation_attachments_quotation ON quotation_attachments (organization_id, quotation_id);

ALTER TABLE quotation_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE quotation_attachments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_quotation_attachments ON quotation_attachments
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON quotation_attachments TO salesmanager_app;
