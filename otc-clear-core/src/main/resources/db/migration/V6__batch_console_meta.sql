-- Batch console metadata: favorites, pinning and user tags live next to the batch itself so that
-- every instance of the tenant sees the same list state.
ALTER TABLE import_batch ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE import_batch ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;

-- Tags are stored as ",a,b," so exact tag matching is a plain LIKE instead of a table scan of a
-- child table; the list stays small (a handful of tags per batch).
ALTER TABLE import_batch ADD COLUMN tags VARCHAR(512);

-- The batch console always pages by creation time inside one tenant.
CREATE INDEX ix_import_batch_tenant_created ON import_batch (tenant_id, created_at DESC);
