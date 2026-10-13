DROP TABLE IF EXISTS `test`.`executed_file`;
CREATE TABLE `test`.`executed_file` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `file` text COLLATE utf8mb4_bin NOT NULL,
    `drops` int(11) NOT NULL,
    `size` bigint(20) NOT NULL,
    `recorded_at` datetime NOT NULL,
    `channel` text COLLATE utf8mb4_bin NOT NULL,
    `title` text COLLATE utf8mb4_bin NOT NULL,
    `channelName` text COLLATE utf8mb4_bin NOT NULL,
    `duration` double NOT NULL,
    `status` varchar(20) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `file` (`file`) USING HASH
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DROP TABLE IF EXISTS `test`.`splitted_file`;
CREATE TABLE `test`.`splitted_file` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `executed_file_id` bigint(20) NOT NULL,
    `file` text COLLATE utf8mb4_bin NOT NULL,
    `size` bigint(20) NOT NULL,
    `duration` double NOT NULL,
    `status` varchar(20) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `file` (`file`) USING HASH,
    KEY `executed_file_id` (`executed_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DROP TABLE IF EXISTS `test`.`created_file`;
CREATE TABLE `test`.`created_file` (
   `id` bigint(20) NOT NULL AUTO_INCREMENT,
   `splitted_file_id` bigint(20) NOT NULL,
   `file` text COLLATE utf8mb4_bin NOT NULL,
   `size` bigint(20) NOT NULL,
   `mime` varchar(100),
   `encoding` varchar(100),
   `status` varchar(20) NOT NULL,
   PRIMARY KEY (`id`),
   UNIQUE KEY `file` (`file`) USING HASH,
   KEY `splitted_file_id` (`splitted_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DROP TABLE IF EXISTS `test`.`program`;
CREATE TABLE `test`.`program` (
  `id` bigint(20) NOT NULL PRIMARY KEY AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `executed_file_id` bigint(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  UNIQUE KEY `name` (`name`) USING HASH,
  KEY `executed_file_id` (`executed_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
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
-- Apply after 064 and 072. History intentionally has no cascading foreign keys to live jobs/artifacts.
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
