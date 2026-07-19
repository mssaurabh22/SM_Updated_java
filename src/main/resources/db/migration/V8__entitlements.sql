-- Part A of the Employee Entitlement plan: organization_entitlements is the ONE deliberate
-- exception to this codebase's usual "every feature table is per-org RLS-protected" rule.
-- This table is not tenant-scoped data an org manages about itself - it's platform-level data
-- ABOUT tenants (which orgs are licensed for which features), so an org's own JWT/RLS session
-- must never be able to read or write its own row here (a tenant must never be able to grant
-- itself a paid feature). It is managed exclusively through the internal, shared-secret-key-
-- protected grant/revoke endpoint (InternalEntitlementController) and read via an explicit
-- org-scoped repository query (EntitlementService), never through the Hibernate tenantFilter
-- or a Postgres RLS policy - hence no ENABLE ROW LEVEL SECURITY / tenant_isolation_* policy
-- here, unlike every other table added since V1. OrganizationEntitlement (the entity) matches
-- this: it extends BaseEntity directly, NOT TenantAware, and carries no @Filter.
--
-- entitlement_code is a plain varchar, not an FK to a catalog table - the catalog itself is
-- kept as a Java enum (FeatureEntitlement), not a DB table, until there are enough entitlement
-- types to justify admin-editing them as data (YAGNI, matching this project's established
-- discipline elsewhere).
CREATE TABLE organization_entitlements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    entitlement_code varchar(50) NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NULL,
    granted_by varchar(255) NULL,
    revoked_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, entitlement_code)
);

CREATE INDEX idx_organization_entitlements_org ON organization_entitlements (organization_id);

-- Still needs basic read/write privilege for the app's restricted DB role (the internal
-- controller's queries run through the same salesmanager_app-connected datasource as every
-- other request) - just not RLS-gated the way every other grant in this app is paired with a
-- tenant_isolation_* policy. No DELETE privilege: rows are revoked (revoked_at set), never
-- hard-deleted, by design.
GRANT SELECT, INSERT, UPDATE ON organization_entitlements TO salesmanager_app;
