-- Lead attachments (file upload field on the Lead form's Additional Details section).
-- storage_key is an opaque handle owned by whichever AttachmentStorageService wrote the file -
-- today that's a relative path under LocalFilesystemAttachmentStorageService's local base
-- directory (see application.yml's attachment.local-storage-dir); swapping in an S3-backed
-- implementation later needs no change to this table.

CREATE TABLE lead_attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    lead_id uuid NOT NULL REFERENCES leads(id),
    file_name varchar(255) NOT NULL,
    storage_key varchar(500) NOT NULL,
    content_type varchar(100) NOT NULL,
    file_size bigint NOT NULL,
    uploaded_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_lead_attachments_org_lead ON lead_attachments (organization_id, lead_id);

-- Row-Level Security, identical pattern to every other tenant table (see V1's comments).
ALTER TABLE lead_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE lead_attachments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_lead_attachments ON lead_attachments
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON lead_attachments TO salesmanager_app;
