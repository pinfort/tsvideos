-- Apply after 067; retained alongside history, including after a live job is reset.
CREATE TABLE IF NOT EXISTS processing_profile_selection (
 attempt_id BIGINT NOT NULL PRIMARY KEY,
 profile VARCHAR(255) NOT NULL,
 rule_id VARCHAR(64) NOT NULL,
 reason VARCHAR(32) NOT NULL,
 selected_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
