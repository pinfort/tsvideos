-- Apply once to an existing installation before deploying issue 64.
CREATE TABLE IF NOT EXISTS processing_job (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 identity_key CHAR(64) CHARACTER SET ascii NOT NULL UNIQUE,
 input_path TEXT COLLATE utf8mb4_bin NOT NULL,
 input_size BIGINT NOT NULL,
 modified_millis BIGINT NOT NULL,
 state VARCHAR(40) NOT NULL,
 executed_file_id BIGINT NULL,
 callback_claim VARCHAR(36) NULL,
 callback_manifest CHAR(64) CHARACTER SET ascii NULL,
 created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 KEY executed_file_id (executed_file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

