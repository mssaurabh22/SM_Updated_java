-- Phase 7: STATE master type (with City -> State linking via master_data.parent_id), a
-- stateId reference on Employee/Lead/Visit, and "creatable" free-text fallback (*_other)
-- fields on Lead/Visit for every master-driven field used during capture.

-- V2's CHECK constraint on master_data.type is a hand-enumerated list, not auto-derived from
-- the Java MasterType enum, so it must be extended explicitly here to allow STATE rows.
ALTER TABLE master_data DROP CONSTRAINT master_data_type_check;
ALTER TABLE master_data ADD CONSTRAINT master_data_type_check CHECK (type IN (
    'INDUSTRY', 'CITY', 'PRODUCT', 'BUSINESS_TYPE', 'DESIGNATION', 'VISIT_PURPOSE',
    'NEXT_ACTION', 'LOST_REASON', 'INTEREST_LEVEL', 'LEAD_SOURCE', 'STATE'
));

ALTER TABLE employees ADD COLUMN state_id uuid NULL REFERENCES master_data(id);

ALTER TABLE leads
  ADD COLUMN state_id uuid NULL REFERENCES master_data(id),
  ADD COLUMN industry_other varchar(255) NULL,
  ADD COLUMN business_type_other varchar(255) NULL,
  ADD COLUMN lead_source_other varchar(255) NULL,
  ADD COLUMN designation_other varchar(255) NULL,
  ADD COLUMN state_other varchar(255) NULL,
  ADD COLUMN city_other varchar(255) NULL,
  ADD COLUMN interest_level_other varchar(255) NULL,
  ADD COLUMN lost_reason_other varchar(255) NULL,
  ADD COLUMN products_other text NULL;

ALTER TABLE visits
  ADD COLUMN state_id uuid NULL REFERENCES master_data(id),
  ADD COLUMN purpose_other varchar(255) NULL,
  ADD COLUMN designation_other varchar(255) NULL,
  ADD COLUMN state_other varchar(255) NULL,
  ADD COLUMN city_other varchar(255) NULL,
  ADD COLUMN interest_level_other varchar(255) NULL,
  ADD COLUMN products_other text NULL;

-- No new RLS/grants needed - these are new columns on already-RLS-protected tables, not new
-- tables (see V1/V2's comments for the tenant_isolation_* policies already covering
-- employees/leads/visits).
