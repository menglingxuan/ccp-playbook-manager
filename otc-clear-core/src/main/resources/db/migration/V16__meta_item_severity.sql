-- A validation rule may block the import instead of only warning about the value.
-- WARN keeps the previous behaviour, ERROR rejects the row.
ALTER TABLE meta_item ADD COLUMN severity VARCHAR(8) DEFAULT 'WARN';
