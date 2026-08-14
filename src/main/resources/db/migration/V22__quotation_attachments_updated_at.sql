-- V20's quotation_attachments table omitted updated_at, which every entity extending
-- common.BaseEntity requires (@UpdateTimestamp) - caught by Hibernate's schema validation at
-- startup. V20 already ran against the shared dev DB, so it's patched forward here rather than
-- edited in place (editing an already-applied migration would break Flyway's checksum check for
-- every environment that already ran it). See lead_attachments (V18) for the correct precedent
-- this should have followed from the start.
ALTER TABLE quotation_attachments ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
