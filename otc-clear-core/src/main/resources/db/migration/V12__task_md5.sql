-- MD5 of the imported file content, shown next to the SHA-256 on the task page
ALTER TABLE import_task ADD COLUMN source_md5 VARCHAR(64);
