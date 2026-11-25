ALTER TABLE kyc_records
    RENAME COLUMN file_url TO storage_key;

UPDATE kyc_records
SET storage_key = regexp_replace(replace(storage_key, chr(92), '/'), '^.*/', '');

ALTER TABLE kyc_records
    ADD COLUMN content_type VARCHAR(100) NOT NULL DEFAULT 'application/octet-stream';

UPDATE kyc_records
SET content_type = CASE
    WHEN lower(storage_key) LIKE '%.pdf' THEN 'application/pdf'
    WHEN lower(storage_key) LIKE '%.jpg' OR lower(storage_key) LIKE '%.jpeg' THEN 'image/jpeg'
    WHEN lower(storage_key) LIKE '%.png' THEN 'image/png'
    ELSE 'application/octet-stream'
END;
