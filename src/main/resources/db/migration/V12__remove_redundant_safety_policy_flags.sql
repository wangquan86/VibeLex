ALTER TABLE meme_safety_policies
    DROP INDEX idx_safety_generate_recommend,
    DROP CHECK chk_safety_detect_enabled,
    DROP CHECK chk_safety_generate_enabled,
    DROP CHECK chk_safety_recommend_enabled,
    DROP COLUMN detect_enabled,
    DROP COLUMN generate_enabled,
    DROP COLUMN recommend_enabled;
