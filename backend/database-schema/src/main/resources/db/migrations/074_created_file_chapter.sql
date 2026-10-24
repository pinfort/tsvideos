-- Apply after 073. Chapters embedded in encoded mp4 files; see backend/docs/video-chapters.md.
-- chapters_probed separates "probed, no chapters" (TRUE with no rows) from "not probed yet" (FALSE).
ALTER TABLE created_file ADD COLUMN IF NOT EXISTS chapters_probed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE IF NOT EXISTS created_file_chapter (
 created_file_id BIGINT NOT NULL,
 chapter_index INT NOT NULL,
 start_seconds DOUBLE NOT NULL,
 end_seconds DOUBLE NOT NULL,
 title TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 PRIMARY KEY (created_file_id, chapter_index),
 CONSTRAINT created_file_chapter_file_fk FOREIGN KEY (created_file_id) REFERENCES created_file(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
