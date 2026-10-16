-- Base schema. Idempotent: safe to apply to an existing installation.
CREATE TABLE IF NOT EXISTS executed_file (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    file text COLLATE utf8mb4_bin NOT NULL,
    drops int(11) NOT NULL,
    size bigint(20) NOT NULL,
    recorded_at datetime NOT NULL,
    channel text COLLATE utf8mb4_bin NOT NULL,
    title text COLLATE utf8mb4_bin NOT NULL,
    channelName text COLLATE utf8mb4_bin NOT NULL,
    duration double NOT NULL,
    status varchar(20) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY file (file) USING HASH
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS splitted_file (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    executed_file_id bigint(20) NOT NULL,
    file text COLLATE utf8mb4_bin NOT NULL,
    size bigint(20) NOT NULL,
    duration double NOT NULL,
    status varchar(20) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY file (file) USING HASH,
    KEY executed_file_id (executed_file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS created_file (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    splitted_file_id bigint(20) NOT NULL,
    file text COLLATE utf8mb4_bin NOT NULL,
    size bigint(20) NOT NULL,
    mime varchar(100),
    encoding varchar(100),
    status varchar(20) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY file (file) USING HASH,
    KEY splitted_file_id (splitted_file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS program (
    id bigint(20) NOT NULL PRIMARY KEY AUTO_INCREMENT,
    name varchar(255) NOT NULL,
    executed_file_id bigint(20) NOT NULL,
    status varchar(20) NOT NULL,
    UNIQUE KEY name (name) USING HASH,
    KEY executed_file_id (executed_file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
