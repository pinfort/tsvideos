-- Apply after 064, in filename order. History intentionally has no (cascading) foreign keys to live jobs/artifacts, so it does not depend on 072.
CREATE TABLE IF NOT EXISTS processing_history_identity (
 job_id BIGINT NOT NULL PRIMARY KEY,
 identity_key CHAR(64) CHARACTER SET ascii NOT NULL,
 input_path TEXT NOT NULL,
 input_size BIGINT NOT NULL,
 modified_millis BIGINT NOT NULL,
 last_attempt INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS processing_attempt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_id BIGINT NOT NULL,
 attempt_number INT NOT NULL,
 kind VARCHAR(20) NOT NULL,
 outcome VARCHAR(20) NOT NULL,
 started_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 ended_at DATETIME(6) NULL,
 activity_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 error_code VARCHAR(40) NULL,
 UNIQUE KEY job_attempt (job_id,attempt_number),
 KEY terminal_history (outcome,ended_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS processing_stage_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 attempt_id BIGINT NOT NULL,
 stage VARCHAR(30) NOT NULL,
 outcome VARCHAR(20) NOT NULL,
 started_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 ended_at DATETIME(6) NULL,
 error_code VARCHAR(40) NULL,
 artifact_type VARCHAR(30) NULL,
 artifact_id BIGINT NULL,
 artifact_path TEXT NULL,
 KEY attempt_stages (attempt_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
