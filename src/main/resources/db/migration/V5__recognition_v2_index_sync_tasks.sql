CREATE TABLE index_sync_tasks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    meme_id BIGINT UNSIGNED NOT NULL,
    operation VARCHAR(16) NOT NULL COMMENT 'UPSERT 或 DELETE',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending, processing, succeeded, failed',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at DATETIME(3) NULL,
    last_error TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_index_sync_tasks_meme_id (meme_id),
    KEY idx_index_sync_tasks_claim (status, next_retry_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
