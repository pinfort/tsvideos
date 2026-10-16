-- Detector results (tags) and which detectors ran. Apply once to an existing installation
-- before deploying the emergency broadcast detector.
CREATE TABLE IF NOT EXISTS executed_file_tag (
    executed_file_id bigint(20) NOT NULL,
    tag varchar(64) NOT NULL,
    PRIMARY KEY (executed_file_id, tag),
    KEY tag (tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS executed_file_check (
    executed_file_id bigint(20) NOT NULL,
    checker varchar(64) NOT NULL,
    checked_at datetime NOT NULL,
    PRIMARY KEY (executed_file_id, checker)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
