ALTER TABLE crawl_checkpoints
  ADD COLUMN active_batch_token VARCHAR(36) NULL,
  ADD COLUMN validation_run TINYINT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN validation_target INT UNSIGNED NULL;

ALTER TABLE crawl_records
  ADD COLUMN batch_token VARCHAR(36) NULL,
  ADD KEY idx_crawl_record_batch(source_code, batch_token, status, next_attempt_at);
