-- Apply after 064_processing_job.sql, before starting processors.
CREATE TABLE IF NOT EXISTS upload_manifest (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_id BIGINT NOT NULL,
 artifact_key CHAR(64) CHARACTER SET ascii NOT NULL,
 destination_key CHAR(64) CHARACTER SET ascii NOT NULL UNIQUE,
 nas_type VARCHAR(40) NOT NULL,
 final_path TEXT NOT NULL,
 temporary_path TEXT NOT NULL,
 source_path TEXT NOT NULL,
 source_size BIGINT NOT NULL,
 source_modified BIGINT NOT NULL,
 source_sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
 checksum_required BOOLEAN NOT NULL,
 state VARCHAR(40) NOT NULL,
 claim_token VARCHAR(36) NULL,
 created_file_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 UNIQUE KEY job_artifact (job_id, artifact_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
