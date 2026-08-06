CREATE TABLE search_rebuild_jobs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    target_index VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'preparing' COMMENT 'preparing, running, succeeded, failed',
    total_items INT UNSIGNED NOT NULL DEFAULT 0,
    succeeded_items INT UNSIGNED NOT NULL DEFAULT 0,
    failed_items INT UNSIGNED NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_search_rebuild_jobs_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE search_rebuild_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id BIGINT UNSIGNED NOT NULL,
    meme_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending, processing, succeeded, failed',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at DATETIME(3) NULL,
    last_error TEXT NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_rebuild_items_job_meme (job_id, meme_id),
    KEY idx_search_rebuild_items_claim (job_id, status, next_retry_at, id),
    CONSTRAINT fk_search_rebuild_items_job
      FOREIGN KEY (job_id) REFERENCES search_rebuild_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
