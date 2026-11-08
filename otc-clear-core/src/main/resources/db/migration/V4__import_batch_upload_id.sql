-- Batches remember the upload they came from, so a repeated submission of the same upload can be
-- detected without treating a fresh import of an identical file as a duplicate.

ALTER TABLE import_batch ADD COLUMN upload_id VARCHAR(36);

CREATE INDEX idx_import_batch_upload ON import_batch (tenant_id, upload_id, sheet_name);
