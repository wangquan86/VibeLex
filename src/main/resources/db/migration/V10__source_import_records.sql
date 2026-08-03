ALTER TABLE source_import_runs
  ADD COLUMN source_code VARCHAR(64) NULL AFTER id,
  ADD COLUMN imported_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER candidate_count,
  ADD COLUMN duplicate_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER imported_count,
  ADD COLUMN ignored_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER duplicate_count,
  ADD COLUMN failed_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER ignored_count;

UPDATE source_import_runs
SET source_code = CASE
  WHEN LOWER(source_name) = 'buzzword' THEN 'buzzword'
  WHEN LOWER(source_name) = 'chime' THEN 'chime'
  ELSE LOWER(REPLACE(source_name, ' ', '-'))
END
WHERE source_code IS NULL;

UPDATE source_import_runs
SET imported_count = candidate_count,
    ignored_count = rejected_count;

ALTER TABLE source_import_runs
  MODIFY COLUMN source_code VARCHAR(64) NOT NULL,
  ADD KEY idx_import_source_started(source_code, started_at);

CREATE TABLE source_import_records (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  import_run_id BIGINT UNSIGNED NOT NULL,
  source_index INT UNSIGNED NOT NULL,
  source_record_key VARCHAR(255) NOT NULL,
  term_raw VARCHAR(255) NOT NULL,
  normalized_term VARCHAR(255) NULL,
  definition_raw TEXT NULL,
  source_url VARCHAR(2048) NULL,
  parser_version VARCHAR(64) NOT NULL,
  processing_note MEDIUMTEXT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  candidate_id BIGINT UNSIGNED NULL,
  duplicate_target_type VARCHAR(32) NULL,
  duplicate_target_id BIGINT UNSIGNED NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  lease_owner VARCHAR(128) NULL,
  lease_until DATETIME(3) NULL,
  processor_stage VARCHAR(64) NULL,
  error_type VARCHAR(128) NULL,
  error_message VARCHAR(2000) NULL,
  ai_provider VARCHAR(128) NULL,
  ai_model VARCHAR(255) NULL,
  processor_version VARCHAR(64) NULL,
  ai_output MEDIUMTEXT NULL,
  processed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_import_record_run FOREIGN KEY (import_run_id)
    REFERENCES source_import_runs(id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_import_record_candidate FOREIGN KEY (candidate_id)
    REFERENCES candidate_entries(id) ON DELETE SET NULL ON UPDATE CASCADE,
  UNIQUE KEY uk_import_record_run_key(import_run_id, source_record_key),
  KEY idx_import_record_claim(status, next_attempt_at, lease_until),
  KEY idx_import_record_run_status(import_run_id, status, id),
  KEY idx_import_record_candidate(candidate_id),
  KEY idx_import_record_term(normalized_term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
