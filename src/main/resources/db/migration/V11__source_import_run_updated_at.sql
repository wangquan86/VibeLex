ALTER TABLE source_import_runs
  ADD COLUMN updated_at DATETIME(3) NULL AFTER finished_at;

UPDATE source_import_runs
SET updated_at = COALESCE(finished_at, started_at);

ALTER TABLE source_import_runs
  MODIFY COLUMN updated_at DATETIME(3) NOT NULL
    DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3);
