-- Optional org logo + custom header/footer text for the generated invoice PDF (Phase 3
-- follow-up). logo_image is stored directly as bytea rather than S3 - this codebase has no
-- file/upload infrastructure yet (see docs: "File attachments on Visits" is itself still
-- deferred pending real S3 credentials), a logo is small (validated <=2MB at the app layer),
-- and it avoids provisioning any new AWS resource for what is a rarely-changed, single-row-
-- per-org asset.
ALTER TABLE organizations
    ADD COLUMN logo_image bytea NULL,
    ADD COLUMN logo_content_type varchar(50) NULL,
    ADD COLUMN invoice_header_text varchar(1000) NULL,
    ADD COLUMN invoice_footer_text varchar(1000) NULL;
