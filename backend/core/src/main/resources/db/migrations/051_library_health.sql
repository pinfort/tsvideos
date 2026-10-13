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
