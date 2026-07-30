UPDATE candidate_entries
SET processing_note = JSON_SET(
    COALESCE(processing_note, JSON_OBJECT()),
    '$.source_name',
    CASE JSON_UNQUOTE(JSON_EXTRACT(processing_note, '$.source_code'))
      WHEN 'popcidian' THEN '波普词典'
      ELSE COALESCE(
          JSON_UNQUOTE(JSON_EXTRACT(processing_note, '$.source_code')),
          '未知来源')
    END)
WHERE source_type = 'crawler'
  AND JSON_EXTRACT(processing_note, '$.source_name') IS NULL;

ALTER TABLE crawl_checkpoints
  DROP CHECK chk_crawl_checkpoint_full,
  DROP COLUMN full_completed,
  DROP COLUMN current_mode;
