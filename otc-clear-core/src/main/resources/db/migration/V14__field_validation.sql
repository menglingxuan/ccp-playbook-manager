-- Validation rules: a meta item of a VALIDATION set carries its pattern type, and fields may bind a
-- boolean expression over rule codes.
ALTER TABLE meta_item ADD COLUMN rule_type VARCHAR(16);

ALTER TABLE base_product_field ADD COLUMN validation_expr VARCHAR(512);

ALTER TABLE product_field ADD COLUMN validation_expr VARCHAR(512);
