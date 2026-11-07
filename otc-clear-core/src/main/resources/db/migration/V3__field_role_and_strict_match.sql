-- Field roles collapse to BUSINESS / MANAGED, the business key becomes a dedicated flag and
-- derived products gain a strict matching switch.

ALTER TABLE base_product_field ADD COLUMN key_flag BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product_field ADD COLUMN key_flag BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE base_product_field SET key_flag = TRUE WHERE role_code = 'KEY';
UPDATE product_field SET key_flag = TRUE WHERE role_code = 'KEY';

UPDATE base_product_field SET role_code = 'BUSINESS' WHERE role_code IN ('TRADE', 'KEY');
UPDATE product_field SET role_code = 'BUSINESS' WHERE role_code IN ('TRADE', 'KEY');

-- Derived products always require their business fields; managed columns stay configurable.
UPDATE product_field SET required_flag = TRUE WHERE role_code = 'BUSINESS';

ALTER TABLE product ADD COLUMN strict_match BOOLEAN NOT NULL DEFAULT FALSE;
