ALTER TABLE candidate_entries
  MODIFY COLUMN import_run_id BIGINT UNSIGNED NULL,
  MODIFY COLUMN import_fingerprint CHAR(64) NULL,
  MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'editing',
  ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'import' AFTER parser_version,
  ADD COLUMN created_by VARCHAR(128) NULL AFTER source_type,
  ADD COLUMN submitted_by VARCHAR(128) NULL AFTER change_set_id,
  ADD COLUMN submitted_at DATETIME(3) NULL AFTER submitted_by,
  ADD COLUMN review_base_version INT UNSIGNED NULL AFTER submitted_at,
  ADD COLUMN reviewed_by VARCHAR(128) NULL AFTER review_base_version,
  ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by,
  ADD COLUMN review_comment VARCHAR(2000) NULL AFTER reviewed_at,
  ADD COLUMN published_meme_id BIGINT UNSIGNED NULL AFTER review_comment,
  ADD CONSTRAINT fk_candidate_published_meme
    FOREIGN KEY (published_meme_id) REFERENCES meme_entries(id)
    ON DELETE SET NULL ON UPDATE CASCADE,
  ADD KEY idx_candidate_review_status(status, submitted_at),
  ADD KEY idx_candidate_published_meme(published_meme_id);

UPDATE candidate_entries c
LEFT JOIN source_import_runs r ON r.id = c.import_run_id
LEFT JOIN entry_change_sets cs ON cs.id = c.change_set_id
SET c.created_by = COALESCE(c.created_by, r.initiated_by, 'system'),
    c.submitted_by = cs.submitted_by,
    c.submitted_at = cs.submitted_at,
    c.review_base_version = cs.base_version,
    c.reviewed_by = cs.reviewed_by,
    c.reviewed_at = cs.reviewed_at,
    c.review_comment = cs.review_comment,
    c.published_meme_id = CASE
      WHEN cs.status = 'approved' THEN cs.meme_id
      WHEN c.status = 'merged' THEN c.duplicate_meme_id
      ELSE NULL
    END,
    c.status = CASE
      WHEN c.status = 'pending' THEN 'editing'
      WHEN c.status = 'rejected' THEN 'returned'
      WHEN c.status = 'merged' THEN 'published'
      WHEN c.status = 'converted' AND cs.status = 'pending_review' THEN 'pending_review'
      WHEN c.status = 'converted' AND cs.status = 'approved' THEN 'published'
      WHEN c.status = 'converted' AND cs.status = 'rejected' THEN 'returned'
      WHEN c.status = 'converted' THEN 'editing'
      ELSE c.status
    END;
