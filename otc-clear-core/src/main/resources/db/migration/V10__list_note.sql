-- Free text remarks shown on the task, batch and export lists. The columns hold up to 2000
-- characters so multi line notes fit; the existing batch note column is widened for the same reason.
ALTER TABLE import_task ADD COLUMN IF NOT EXISTS note VARCHAR(2000);
ALTER TABLE export_job ADD COLUMN IF NOT EXISTS note VARCHAR(2000);
ALTER TABLE import_batch ALTER COLUMN note SET DATA TYPE VARCHAR(2000);
