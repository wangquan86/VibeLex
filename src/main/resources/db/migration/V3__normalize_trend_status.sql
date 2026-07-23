UPDATE meme_entries
SET status = 'archived'
WHERE trend_status IN ('archived', 'obsolete')
  AND status = 'published';

UPDATE meme_entries
SET trend_status = 'untracked'
WHERE trend_status IN ('active', 'archived', 'obsolete');

ALTER TABLE meme_entries
    MODIFY COLUMN trend_status VARCHAR(32) NOT NULL DEFAULT 'untracked'
        COMMENT '趋势状态：untracked、emerging、growing、stable、declining';
