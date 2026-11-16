-- Meta sets gain a kind: VALUE sets hold plain values, FORMULA sets hold formula templates.
ALTER TABLE meta_set ADD COLUMN set_type VARCHAR(16) NOT NULL DEFAULT 'VALUE';

-- Meta items gain the content column: the value text of a VALUE set or the formula of a FORMULA set.
ALTER TABLE meta_item ADD COLUMN content VARCHAR(512);

-- Existing items predate the column: for reference-less sets the code was the value itself.
UPDATE meta_item SET content = code WHERE ref_set_code IS NULL AND content IS NULL;
