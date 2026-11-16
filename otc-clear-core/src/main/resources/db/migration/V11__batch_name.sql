ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS name VARCHAR(256);

-- Existing batches get a unique default name inside their task; the oldest one keeps the plain
-- "Untitled". Batches without a task are numbered among themselves.
UPDATE import_batch b
SET name = 'Untitled' || CASE
        WHEN (SELECT COUNT(*) FROM import_batch o
              WHERE o.tenant_id = b.tenant_id
                AND (o.task_id = b.task_id OR (o.task_id IS NULL AND b.task_id IS NULL))
                AND (o.created_at < b.created_at
                     OR (o.created_at = b.created_at AND o.id < b.id))) = 0
            THEN ''
        ELSE ' ' || CAST((SELECT COUNT(*) FROM import_batch o
                          WHERE o.tenant_id = b.tenant_id
                            AND (o.task_id = b.task_id OR (o.task_id IS NULL AND b.task_id IS NULL))
                            AND (o.created_at < b.created_at
                                 OR (o.created_at = b.created_at AND o.id < b.id))) + 1 AS VARCHAR)
    END
WHERE b.name IS NULL;

ALTER TABLE import_batch ALTER COLUMN name SET NOT NULL;

-- Names are unique inside one task; batches without a task stay unconstrained by the unique index.
CREATE UNIQUE INDEX ux_import_batch_task_name ON import_batch (tenant_id, task_id, name);