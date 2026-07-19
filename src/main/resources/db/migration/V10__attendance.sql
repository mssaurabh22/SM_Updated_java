-- Part B.4 of the Employee Entitlement plan: clock-in/clock-out attendance records. Status
-- (Present/Absent/On Leave/Holiday/Weekend) is deliberately NOT a column here - it's derived
-- at read time by attendance.AttendanceService, cross-referencing this table with the
-- leave_requests and holidays tables from V9 - same "compute, don't denormalize a status that
-- can drift" discipline as employee_leave_balances' allocated-only storage (V9).
CREATE TABLE attendance_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    employee_id uuid NOT NULL REFERENCES employees(id),
    attendance_date date NOT NULL,
    check_in_at timestamptz NULL,
    check_out_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (employee_id, attendance_date)
);

CREATE INDEX idx_attendance_records_employee_date ON attendance_records (organization_id, employee_id, attendance_date);

ALTER TABLE attendance_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_records FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_attendance_records ON attendance_records
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON attendance_records TO salesmanager_app;
