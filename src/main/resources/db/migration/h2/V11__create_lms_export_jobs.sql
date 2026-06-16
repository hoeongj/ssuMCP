CREATE TABLE lms_export_jobs (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    student_id      VARCHAR(64)  NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    payload         TEXT         NOT NULL,
    file_path       VARCHAR(512),
    file_count      INT,
    total_bytes     BIGINT,
    failure_reason  TEXT,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX idx_lms_export_jobs_status ON lms_export_jobs(status);
CREATE INDEX idx_lms_export_jobs_expires_at ON lms_export_jobs(expires_at);
