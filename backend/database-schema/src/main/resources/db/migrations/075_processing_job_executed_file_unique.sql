-- Apply after 074 with every processor and callback stopped. At most one job per executed file (#108), any number unlinked.
-- Run the duplicate preflight in backend/docs/processing-idempotency.md first. With duplicates this ALTER fails as a whole
-- (no row is deleted and the old index is kept). Never use ALTER IGNORE, which would silently drop rows.
ALTER TABLE processing_job
 DROP INDEX IF EXISTS executed_file_id,
 ADD UNIQUE INDEX IF NOT EXISTS executed_file_id_unique (executed_file_id);
