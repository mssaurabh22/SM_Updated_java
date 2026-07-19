-- Part B of the Employee Entitlement plan: the Leave/Attendance/Approvals module's schema.
-- Every table here is normal per-org tenant data (unlike organization_entitlements in
-- V8__entitlements.sql, Part A's one deliberate RLS exception) - so every table below follows
-- the same tenant_isolation_* RLS pattern as every table since V1.

-- Employee.managerId: self-referential, nullable, raw-id (not a JPA relation) - same
-- "raw id, service-layer-validated" pattern already used for designation_id/city_id/state_id.
-- Drives leave-request approval routing (leave.LeaveRequestService): the requester's managerId
-- is the resolved approver, with any ADMIN as a fallback/override.
ALTER TABLE employees ADD COLUMN manager_id uuid NULL REFERENCES employees(id);

-- Leave Types are fully admin-configurable per org (plan B.1a) - a dedicated table, not
-- shoehorned into the generic master_data table, since it needs its own dedicated fields
-- (default_allocation_days) that master_data has no room for. Soft-delete only (is_active) -
-- a leave type already referenced by past leave_requests/employee_leave_balances is never
-- hard-deleted.
CREATE TABLE leave_types (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    name varchar(100) NOT NULL,
    code varchar(50) NOT NULL,
    default_allocation_days numeric(5,1) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code)
);

ALTER TABLE leave_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_types FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_leave_types ON leave_types
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON leave_types TO salesmanager_app;

-- Company holiday calendar - admin-configurable, needed so leave day-counts know which days
-- inside a requested range aren't working days (alongside plain Saturday/Sunday weekends).
CREATE TABLE holidays (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    name varchar(255) NOT NULL,
    holiday_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, holiday_date)
);

ALTER TABLE holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE holidays FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_holidays ON holidays
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON holidays TO salesmanager_app;

-- Stores ONLY the allocation (admin-set, defaults from leave_types, adjustable per-employee).
-- "Used"/"remaining" are NEVER stored here - always computed at read time as
-- SUM(leave_requests.total_days) WHERE status='APPROVED' for that employee+type+year (see
-- EmployeeLeaveBalanceService#getBalanceSummary) - storing a running "used" counter would drift
-- the moment a request is edited/cancelled/approved after the fact.
CREATE TABLE employee_leave_balances (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    employee_id uuid NOT NULL REFERENCES employees(id),
    leave_type_id uuid NOT NULL REFERENCES leave_types(id),
    year integer NOT NULL,
    allocated_days numeric(5,1) NOT NULL,
    carried_forward_days numeric(5,1) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (employee_id, leave_type_id, year)
);

CREATE INDEX idx_employee_leave_balances_employee ON employee_leave_balances (organization_id, employee_id, year);

ALTER TABLE employee_leave_balances ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_leave_balances FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_employee_leave_balances ON employee_leave_balances
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON employee_leave_balances TO salesmanager_app;

-- approver_id is the resolved approver SNAPSHOTTED at submission time (the requester's
-- managerId as of that moment, or NULL if they had none - a NULL approver_id request is one
-- only an ADMIN can act on, since there's no manager to fall back from). decided_by_id is
-- whoever ACTUALLY approved/rejected it - could be the resolved manager, or an ADMIN acting as
-- override/fallback - these can differ, which is exactly why both columns exist.
CREATE TABLE leave_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    employee_id uuid NOT NULL REFERENCES employees(id),
    leave_type_id uuid NOT NULL REFERENCES leave_types(id),
    start_date date NOT NULL,
    end_date date NOT NULL,
    total_days numeric(5,1) NOT NULL,
    reason varchar(500) NULL,
    status varchar(20) NOT NULL,
    approver_id uuid NULL REFERENCES employees(id),
    decided_by_id uuid NULL REFERENCES employees(id),
    decided_at timestamptz NULL,
    decision_note varchar(500) NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_leave_requests_employee ON leave_requests (organization_id, employee_id);
CREATE INDEX idx_leave_requests_approver ON leave_requests (organization_id, approver_id, status);
CREATE INDEX idx_leave_requests_status ON leave_requests (organization_id, status);

ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_requests FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_leave_requests ON leave_requests
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON leave_requests TO salesmanager_app;

-- A NEW, separate history table from the Lead-centric activity_log (V7) - deliberately not
-- reused/repurposed, since that table's schema is lead_id NOT NULL and Leave/Attendance events
-- have no associated Lead at all (see employeeactivity.EmployeeActivityLog's class javadoc).
CREATE TABLE employee_activity_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    employee_id uuid NOT NULL REFERENCES employees(id),
    type varchar(30) NOT NULL,
    actor_id uuid NULL REFERENCES employees(id),
    description varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_employee_activity_log_org_employee ON employee_activity_log (organization_id, employee_id);

ALTER TABLE employee_activity_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_activity_log FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_employee_activity_log ON employee_activity_log
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON employee_activity_log TO salesmanager_app;
