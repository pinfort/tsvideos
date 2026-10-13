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

USE test;
-- Apply to the existing application database before deploying issue 53.
-- program.id must remain signed BIGINT, and program must use InnoDB.
CREATE TABLE IF NOT EXISTS library_collection (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 name_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 UNIQUE KEY library_collection_name (name_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS library_tag (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 name_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 UNIQUE KEY library_tag_name (name_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS library_collection_program (
 label_id BIGINT NOT NULL,
 program_id BIGINT NOT NULL,
 PRIMARY KEY (label_id, program_id),
 KEY library_collection_program_program (program_id),
 CONSTRAINT library_collection_program_label_fk FOREIGN KEY (label_id) REFERENCES library_collection(id) ON DELETE CASCADE,
 CONSTRAINT library_collection_program_program_fk FOREIGN KEY (program_id) REFERENCES program(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS library_tag_program (
 label_id BIGINT NOT NULL,
 program_id BIGINT NOT NULL,
 PRIMARY KEY (label_id, program_id),
 KEY library_tag_program_program (program_id),
 CONSTRAINT library_tag_program_label_fk FOREIGN KEY (label_id) REFERENCES library_tag(id) ON DELETE CASCADE,
 CONSTRAINT library_tag_program_program_fk FOREIGN KEY (program_id) REFERENCES program(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
