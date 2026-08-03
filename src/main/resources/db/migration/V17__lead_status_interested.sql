-- Adds LeadStatus.INTERESTED: a lead whose Interest Level isn't Hot is locked to this status
-- (see LeadService#effectiveStatusFor/updateStatus) instead of progressing through the normal
-- pipeline stages. Same DROP/ADD-with-the-same-auto-generated-name pattern already used in
-- V6__state_and_creatable_fields.sql for master_data_type_check.
ALTER TABLE leads DROP CONSTRAINT leads_status_check;
ALTER TABLE leads ADD CONSTRAINT leads_status_check CHECK (status IN (
    'NEW', 'CONTACTED', 'NEGOTIATION', 'INTERESTED', 'LOST', 'CLOSED_WON', 'LAPSED'
));
