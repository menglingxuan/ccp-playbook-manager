-- FIELD_TAG and CONV_METHOD were created by the first seed as sample dictionaries for the templates.
-- They are ordinary data the user may rename, extend or delete, so they must not be protected like the
-- value types and roles the application itself relies on (those stay built_in = TRUE).
UPDATE meta_set SET built_in = FALSE WHERE code IN ('FIELD_TAG', 'CONV_METHOD');
