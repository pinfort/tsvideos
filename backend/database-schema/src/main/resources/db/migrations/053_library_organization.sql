-- Manual collections/tags; see backend/docs/library-organization.md. Apply before deploying the feature.
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
