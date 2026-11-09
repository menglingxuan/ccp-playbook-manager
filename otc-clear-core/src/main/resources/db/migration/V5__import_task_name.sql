ALTER TABLE import_task ADD COLUMN name VARCHAR(256) NOT NULL DEFAULT 'Untitled';

-- Existing tasks get a unique default name; the oldest one keeps the plain "Untitled".
UPDATE import_task t
SET name = 'Untitled' || CASE
        WHEN (SELECT COUNT(*) FROM import_task o
              WHERE o.tenant_id = t.tenant_id
                AND (o.created_at < t.created_at OR (o.created_at = t.created_at AND o.id < t.id))) = 0
            THEN ''
        ELSE ' ' || CAST((SELECT COUNT(*) FROM import_task o
                          WHERE o.tenant_id = t.tenant_id
                            AND (o.created_at < t.created_at
                                 OR (o.created_at = t.created_at AND o.id < t.id))) + 1 AS VARCHAR)
    END;

CREATE UNIQUE INDEX ux_import_task_tenant_name ON import_task (tenant_id, name);
