-- Apply after 064, 072 and 067. Evidence survives reset and is never rewritten.
ALTER TABLE processing_job ADD COLUMN IF NOT EXISTS processing_claim VARCHAR(36) NULL;
CREATE TABLE IF NOT EXISTS processing_checkpoint (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_id BIGINT NOT NULL,
 attempt_id BIGINT NOT NULL,
 stage VARCHAR(40) NOT NULL,
 evidence LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 KEY job_stage(job_id,stage,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS processing_checkpoint_invalidation (
 checkpoint_id BIGINT NOT NULL PRIMARY KEY,
 reason VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS processing_callback_input (
 job_id BIGINT NOT NULL PRIMARY KEY,
 manifest LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
