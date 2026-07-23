CREATE TABLE meme_entries (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_code VARCHAR(64) NOT NULL, canonical_term VARCHAR(255) NOT NULL,
 normalized_term VARCHAR(255) NOT NULL, language_code VARCHAR(16) NOT NULL DEFAULT 'zh-CN', category VARCHAR(32) NOT NULL,
 domain_tags JSON NULL, origin_summary VARCHAR(1000) NULL, trend_status VARCHAR(32) NOT NULL DEFAULT 'active',
 heat_score DECIMAL(5,2) NULL, status VARCHAR(32) NOT NULL DEFAULT 'published', current_version INT UNSIGNED NOT NULL DEFAULT 0,
 created_by VARCHAR(128) NULL, reviewed_by VARCHAR(128) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), published_at DATETIME(3) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_meme_code(meme_code), UNIQUE KEY uk_normalized_term_language(normalized_term,language_code),
 KEY idx_category_status(category,status), KEY idx_trend_status(trend_status), KEY idx_published_at(published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meme_senses (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NOT NULL, sense_no TINYINT UNSIGNED NOT NULL DEFAULT 1,
 short_definition VARCHAR(500) NOT NULL, definition TEXT NOT NULL, usage_context JSON NULL, non_usage_context JSON NULL,
 semantic_tags JSON NULL, emotion_tags JSON NULL, safety_policy_override JSON NULL, polarity VARCHAR(32) NOT NULL DEFAULT 'neutral',
 formality VARCHAR(32) NOT NULL DEFAULT 'informal', status VARCHAR(32) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id), CONSTRAINT fk_meme_senses_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 UNIQUE KEY uk_meme_sense_no(meme_id,sense_no), UNIQUE KEY uk_sense_meme(id,meme_id), KEY idx_meme_sense_status(meme_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meme_variants (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NOT NULL, sense_id BIGINT UNSIGNED NULL,
 variant VARCHAR(255) NOT NULL, normalized_variant VARCHAR(255) NOT NULL, variant_type VARCHAR(32) NOT NULL,
 confidence DECIMAL(5,4) NOT NULL DEFAULT 1.0000, source_method VARCHAR(32) NOT NULL DEFAULT 'editorial', status VARCHAR(32) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id), CONSTRAINT fk_meme_variants_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 CONSTRAINT fk_meme_variants_sense FOREIGN KEY(sense_id,meme_id) REFERENCES meme_senses(id,meme_id) ON DELETE CASCADE ON UPDATE CASCADE,
 UNIQUE KEY uk_meme_normalized_variant_type(meme_id,normalized_variant,variant_type), KEY idx_normalized_variant(normalized_variant),
 KEY idx_variant_meme_status(meme_id,status), KEY idx_variant_type(variant_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meme_examples (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NOT NULL, sense_id BIGINT UNSIGNED NULL,
 example_text VARCHAR(2000) NOT NULL, example_role VARCHAR(32) NOT NULL DEFAULT 'positive', explanation VARCHAR(1000) NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'approved', created_by VARCHAR(128) NULL, reviewed_by VARCHAR(128) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id), CONSTRAINT fk_meme_examples_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 CONSTRAINT fk_meme_examples_sense FOREIGN KEY(sense_id,meme_id) REFERENCES meme_senses(id,meme_id) ON DELETE CASCADE ON UPDATE CASCADE,
 KEY idx_example_meme_status(meme_id,status), KEY idx_example_sense(sense_id), KEY idx_example_role(example_role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meme_match_rules (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NOT NULL, sense_id BIGINT UNSIGNED NULL,
 rule_type VARCHAR(32) NOT NULL, rule_value TEXT NOT NULL, rule_config JSON NULL, weight DECIMAL(6,4) NOT NULL DEFAULT 1.0000,
 threshold DECIMAL(6,4) NULL, priority INT NOT NULL DEFAULT 100, enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
 created_by VARCHAR(128) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), PRIMARY KEY(id),
 CONSTRAINT chk_match_rule_enabled CHECK(enabled IN(0,1)),
 CONSTRAINT fk_meme_match_rules_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 CONSTRAINT fk_meme_match_rules_sense FOREIGN KEY(sense_id,meme_id) REFERENCES meme_senses(id,meme_id) ON DELETE CASCADE ON UPDATE CASCADE,
 KEY idx_rule_meme_enabled(meme_id,enabled), KEY idx_rule_sense_enabled(sense_id,enabled), KEY idx_rule_type_enabled(rule_type,enabled), KEY idx_rule_priority(priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meme_safety_policies (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NOT NULL, profanity TINYINT UNSIGNED NOT NULL DEFAULT 0,
 offense TINYINT UNSIGNED NOT NULL DEFAULT 0, risk_tags JSON NULL, risk_level VARCHAR(16) NOT NULL DEFAULT 'low',
 detect_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1, display_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
 generate_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1, recommend_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
 moderation_policy VARCHAR(32) NOT NULL DEFAULT 'normal', notes VARCHAR(2000) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), PRIMARY KEY(id),
 CONSTRAINT chk_safety_profanity CHECK(profanity IN(0,1)), CONSTRAINT chk_safety_offense CHECK(offense IN(0,1)),
 CONSTRAINT chk_safety_detect_enabled CHECK(detect_enabled IN(0,1)), CONSTRAINT chk_safety_display_enabled CHECK(display_enabled IN(0,1)),
 CONSTRAINT chk_safety_generate_enabled CHECK(generate_enabled IN(0,1)), CONSTRAINT chk_safety_recommend_enabled CHECK(recommend_enabled IN(0,1)),
 CONSTRAINT fk_meme_safety_policies_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 UNIQUE KEY uk_safety_policy_meme(meme_id), KEY idx_safety_risk_level(risk_level), KEY idx_safety_generate_recommend(generate_enabled,recommend_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meme_evidence (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NOT NULL, sense_id BIGINT UNSIGNED NULL,
 source_layer VARCHAR(32) NOT NULL, source_name VARCHAR(255) NOT NULL, source_url VARCHAR(2048) NULL, evidence_role VARCHAR(32) NOT NULL,
 evidence_note VARCHAR(1000) NULL, observed_at DATETIME(3) NULL, confidence DECIMAL(5,4) NULL, status VARCHAR(32) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(id),
 CONSTRAINT fk_meme_evidence_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 CONSTRAINT fk_meme_evidence_sense FOREIGN KEY(sense_id,meme_id) REFERENCES meme_senses(id,meme_id) ON DELETE CASCADE ON UPDATE CASCADE,
 KEY idx_evidence_meme_role(meme_id,evidence_role), KEY idx_evidence_source_layer(source_layer), KEY idx_evidence_observed_at(observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meme_revisions (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NOT NULL, version INT UNSIGNED NOT NULL,
 change_type VARCHAR(32) NOT NULL, change_summary VARCHAR(1000) NULL, snapshot JSON NOT NULL, changed_by VARCHAR(128) NULL,
 reviewed_by VARCHAR(128) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(id),
 CONSTRAINT fk_meme_revisions_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 UNIQUE KEY uk_meme_version(meme_id,version), KEY idx_revision_meme_created(meme_id,created_at), KEY idx_revision_change_type(change_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE entry_change_sets (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, meme_id BIGINT UNSIGNED NULL, change_type VARCHAR(32) NOT NULL,
 base_version INT UNSIGNED NULL, proposed_snapshot JSON NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'draft', change_summary VARCHAR(1000) NULL,
 created_by VARCHAR(128) NOT NULL, submitted_by VARCHAR(128) NULL, submitted_at DATETIME(3) NULL, reviewed_by VARCHAR(128) NULL,
 reviewed_at DATETIME(3) NULL, review_comment VARCHAR(2000) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), PRIMARY KEY(id),
 CONSTRAINT fk_change_set_meme FOREIGN KEY(meme_id) REFERENCES meme_entries(id) ON DELETE RESTRICT ON UPDATE CASCADE,
 KEY idx_change_set_status(status,submitted_at), KEY idx_change_set_meme_status(meme_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_import_runs (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, source_name VARCHAR(255) NOT NULL DEFAULT 'CHIME', source_url VARCHAR(2048) NOT NULL,
 source_version VARCHAR(255) NOT NULL DEFAULT 'manual-local', source_commit VARCHAR(128) NULL, file_name VARCHAR(512) NOT NULL,
 file_hash VARCHAR(128) NOT NULL, import_fingerprint CHAR(64) NOT NULL, attempt_no INT UNSIGNED NOT NULL DEFAULT 1,
 parser_version VARCHAR(64) NOT NULL, license_status VARCHAR(32) NOT NULL DEFAULT 'approved', license_snapshot TEXT NULL,
 upstream_rights_note VARCHAR(2000) NOT NULL DEFAULT 'V1 manual CHIME import approved', license_checked_by VARCHAR(128) NOT NULL DEFAULT 'system',
 license_checked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), status VARCHAR(32) NOT NULL DEFAULT 'running',
 total_count INT UNSIGNED NOT NULL DEFAULT 0, accepted_count INT UNSIGNED NOT NULL DEFAULT 0, rejected_count INT UNSIGNED NOT NULL DEFAULT 0,
 candidate_count INT UNSIGNED NOT NULL DEFAULT 0, error_summary VARCHAR(2000) NULL, initiated_by VARCHAR(128) NOT NULL,
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), finished_at DATETIME(3) NULL, PRIMARY KEY(id),
 UNIQUE KEY uk_import_id_fingerprint(id,import_fingerprint), UNIQUE KEY uk_import_attempt(import_fingerprint,attempt_no),
 KEY idx_import_fingerprint(import_fingerprint,status), KEY idx_import_status_started(status,started_at), KEY idx_import_license_status(license_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE candidate_entries (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, import_run_id BIGINT UNSIGNED NOT NULL, import_fingerprint CHAR(64) NOT NULL,
 source_record_key VARCHAR(255) NOT NULL, term_raw VARCHAR(255) NOT NULL, normalized_term VARCHAR(255) NOT NULL,
 definition_raw TEXT NULL, source_url VARCHAR(2048) NULL, parser_version VARCHAR(64) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'pending', duplicate_meme_id BIGINT UNSIGNED NULL, change_set_id BIGINT UNSIGNED NULL,
 processing_note VARCHAR(2000) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), PRIMARY KEY(id),
 CONSTRAINT fk_candidate_import_run FOREIGN KEY(import_run_id,import_fingerprint) REFERENCES source_import_runs(id,import_fingerprint) ON DELETE RESTRICT ON UPDATE CASCADE,
 CONSTRAINT fk_candidate_duplicate_meme FOREIGN KEY(duplicate_meme_id) REFERENCES meme_entries(id) ON DELETE SET NULL ON UPDATE CASCADE,
 CONSTRAINT fk_candidate_change_set FOREIGN KEY(change_set_id) REFERENCES entry_change_sets(id) ON DELETE SET NULL ON UPDATE CASCADE,
 UNIQUE KEY uk_candidate_source_record(import_fingerprint,source_record_key), KEY idx_candidate_status(status,created_at), KEY idx_candidate_normalized(normalized_term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
