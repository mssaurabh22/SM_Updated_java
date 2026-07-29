-- Invoicing module, Phase 3: the seller header shown on a generated invoice PDF. Nullable,
-- no format validation - "lightweight" v1, no GST-compliance rigor. Added directly to
-- organizations (same precedent as theme_settings: singular, one-per-org, admin-editable
-- config), not a separate profile table. No RLS needed - organizations itself has none,
-- per V1__init_schema.sql's own reasoning ("has no organization_id column - it IS the tenant").
ALTER TABLE organizations
    ADD COLUMN billing_address varchar(500),
    ADD COLUMN billing_gstin varchar(20),
    ADD COLUMN billing_phone varchar(50);
