-- Business record id restricted to the business (trade) fields; managed columns are excluded
ALTER TABLE test_case ADD COLUMN core_business_record_id VARCHAR(64);
