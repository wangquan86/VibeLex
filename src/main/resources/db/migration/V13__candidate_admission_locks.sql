CREATE TABLE candidate_admission_locks (
  normalized_term VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (normalized_term)
);
