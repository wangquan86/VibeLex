-- V3.2 one-time maintenance. This file is intentionally outside Flyway.
-- It only applies to the audited production snapshot from 2026-08-06.

DELIMITER //

CREATE PROCEDURE v32_entry_lifecycle_cleanup()
BEGIN
  DECLARE archived_count INT DEFAULT 0;
  DECLARE disabled_count INT DEFAULT 0;
  DECLARE original_candidate_count INT DEFAULT 0;
  DECLARE transient_candidate_count INT DEFAULT 0;
  DECLARE transient_reference_count INT DEFAULT 0;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  SELECT COUNT(*) INTO disabled_count
  FROM meme_entries
  WHERE status = 'disabled';

  SELECT COUNT(*) INTO archived_count
  FROM meme_entries
  WHERE status = 'archived'
    AND id = 4473
    AND meme_code = 'MEME_004473'
    AND canonical_term = '蚌埠住'
    AND current_version = 1;

  SELECT COUNT(*) INTO original_candidate_count
  FROM candidate_entries
  WHERE id = 1461
    AND term_raw = '蚌埠住'
    AND status = 'published'
    AND source_type = 'import'
    AND import_run_id = 2
    AND published_meme_id = 4473;

  SELECT COUNT(*) INTO transient_candidate_count
  FROM candidate_entries
  WHERE id = 4611
    AND term_raw = '蚌埠住了'
    AND status = 'published'
    AND source_type = 'manual'
    AND parser_version = 'withdraw-v1'
    AND published_meme_id = 1237
    AND JSON_UNQUOTE(JSON_EXTRACT(processing_note, '$.withdrawal.entry_id')) = '4473';

  SELECT
      (SELECT COUNT(*) FROM source_import_records WHERE candidate_id = 4611)
    + (SELECT COUNT(*) FROM crawl_records WHERE candidate_id = 4611)
  INTO transient_reference_count;

  IF disabled_count <> 0
     OR archived_count <> 1
     OR original_candidate_count <> 1
     OR transient_candidate_count <> 1
     OR transient_reference_count <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V3.2 lifecycle cleanup precondition failed; no data was changed';
  END IF;

  START TRANSACTION;

  UPDATE candidate_entries
  SET status = 'editing',
      duplicate_meme_id = NULL,
      submitted_by = NULL,
      submitted_at = NULL,
      review_base_version = NULL,
      reviewed_by = NULL,
      reviewed_at = NULL,
      review_comment = NULL,
      processing_note = JSON_SET(
          CAST(processing_note AS JSON),
          '$.withdrawal.entry_id', 4473,
          '$.withdrawal.meme_code', 'MEME_004473',
          '$.withdrawal.version', 1,
          '$.withdrawal.withdrawn_at', '2026-08-06T14:40:11.995')
  WHERE id = 1461
    AND status = 'published'
    AND source_type = 'import'
    AND import_run_id = 2
    AND published_meme_id = 4473;

  IF ROW_COUNT() <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Failed to reactivate candidate 1461';
  END IF;

  INSERT INTO meme_revisions(
      meme_id, version, change_type, change_summary, snapshot,
      changed_by, reviewed_by, created_at)
  SELECT
      4473,
      2,
      'archive',
      '撤回至候选池',
      JSON_SET(r.snapshot, '$.meme_entry.status', 'archived', '$.meme_entry.current_version', 2),
      'editor01',
      'editor01',
      '2026-08-06 14:40:12.001'
  FROM meme_revisions r
  WHERE r.meme_id = 4473 AND r.version = 1
    AND NOT EXISTS (
        SELECT 1 FROM meme_revisions existing
        WHERE existing.meme_id = 4473 AND existing.version = 2);

  IF ROW_COUNT() <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Failed to add archive revision for entry 4473';
  END IF;

  UPDATE meme_entries
  SET current_version = 2
  WHERE id = 4473 AND status = 'archived' AND current_version = 1;

  IF ROW_COUNT() <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Failed to advance entry 4473 version';
  END IF;

  DELETE FROM candidate_entries
  WHERE id = 4611
    AND status = 'published'
    AND source_type = 'manual'
    AND parser_version = 'withdraw-v1'
    AND published_meme_id = 1237;

  IF ROW_COUNT() <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Failed to remove transient candidate 4611';
  END IF;

  INSERT INTO index_sync_tasks(meme_id, operation, status, retry_count, next_retry_at)
  VALUES (4473, 'DELETE', 'pending', 0, NOW(3))
  ON DUPLICATE KEY UPDATE
    operation = 'DELETE',
    status = 'pending',
    retry_count = 0,
    next_retry_at = NOW(3),
    last_error = NULL,
    finished_at = NULL;

  IF (SELECT COUNT(*) FROM candidate_entries WHERE id = 1461 AND status = 'editing') <> 1
     OR (SELECT COUNT(*) FROM candidate_entries WHERE id = 4611) <> 0
     OR (SELECT COUNT(*) FROM meme_revisions WHERE meme_id = 4473 AND version = 2 AND change_type = 'archive') <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V3.2 lifecycle cleanup verification failed';
  END IF;

  COMMIT;
END//

CALL v32_entry_lifecycle_cleanup()//
DROP PROCEDURE v32_entry_lifecycle_cleanup//

DELIMITER ;

SELECT status, COUNT(*) AS total
FROM meme_entries
GROUP BY status
ORDER BY status;

SELECT status, source_type, COUNT(*) AS total
FROM candidate_entries
GROUP BY status, source_type
ORDER BY status, source_type;
