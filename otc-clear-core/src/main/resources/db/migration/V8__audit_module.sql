-- Audit entries are grouped by functional module so the audit panel can filter them.
ALTER TABLE audit_log ADD COLUMN module VARCHAR(32);

-- Existing rows predate the module filter: map them from the entity they were written for.
UPDATE audit_log SET module = CASE
    WHEN entity_type IN ('BASE_PRODUCT', 'BASE_FIELD', 'PRODUCT', 'PRODUCT_FIELD') THEN 'PRODUCT_DEFINITION'
    WHEN entity_type IN ('META_SET', 'META_ITEM') THEN 'META_DICTIONARY'
    WHEN entity_type = 'TASK' THEN 'TASK'
    WHEN entity_type = 'BATCH' THEN 'BATCH'
    WHEN entity_type = 'CASE' THEN 'CASE'
    WHEN entity_type = 'EXPORT' THEN 'EXPORT'
    WHEN entity_type = 'OUTPUT' THEN 'OUTPUT'
    WHEN entity_type = 'EXTERNAL_JOB' THEN 'EXTERNAL_JOB'
    ELSE 'OTHER'
END;

ALTER TABLE audit_log ALTER COLUMN module SET NOT NULL;

CREATE INDEX ix_audit_tenant_module ON audit_log (tenant_id, module, created_at);
