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
CREATE TABLE IF NOT EXISTS health_verification_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    state VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(40) NULL,
    active_marker INT NULL,
    UNIQUE KEY uq_health_active (active_marker)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS health_verification_finding (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    file_id BIGINT NULL,
    file TEXT NULL,
    status VARCHAR(30) NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    expected_bytes BIGINT NULL,
    actual_bytes BIGINT NULL,
    reason VARCHAR(255) NULL,
    KEY ix_health_finding_run (run_id),
    CONSTRAINT fk_health_finding_run FOREIGN KEY (run_id) REFERENCES health_verification_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
