-- Apply after 067. Retained quality evidence intentionally survives live job/reset deletion.
CREATE TABLE IF NOT EXISTS processing_quality_report (
 attempt_id BIGINT NOT NULL PRIMARY KEY,
 job_id BIGINT NOT NULL,
 checked_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 action VARCHAR(20) NOT NULL,
 source_path TEXT NOT NULL,
 identity_key CHAR(64) CHARACTER SET ascii NOT NULL,
 findings_json LONGTEXT NOT NULL,
 KEY quality_job (job_id,attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
