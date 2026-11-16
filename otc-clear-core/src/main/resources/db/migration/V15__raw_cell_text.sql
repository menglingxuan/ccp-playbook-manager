-- Raw cell texts read at import time: business processing uses them as the value text.
ALTER TABLE test_case ADD COLUMN raw_json TEXT;
