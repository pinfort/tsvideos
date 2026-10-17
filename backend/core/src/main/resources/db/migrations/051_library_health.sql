-- Library health verification runs/findings; see backend/docs/library-health.md. Apply before deploying the feature.
-- active_marker is 1 only while a run is RUNNING and NULL otherwise. The UNIQUE key admits any number of NULLs
-- (MariaDB/InnoDB), so it permits at most one active run across processes.
CREATE TABLE IF NOT EXISTS health_verification_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    state VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(40) NULL,
    active_marker INT NULL,
    UNIQUE KEY uq_health_active (active_marker),
    KEY ix_health_run_completed (state, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- program_id deliberately has no FK: findings are an immutable snapshot that must survive program deletion
-- (shown as a removed program). Findings are kept for every run; there is no automatic pruning.
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
