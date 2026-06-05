CREATE TABLE action_audit (
    id           BIGSERIAL                     PRIMARY KEY,
    student_id   VARCHAR(64)                   NOT NULL,
    action_type  VARCHAR(64)                   NOT NULL,
    status       VARCHAR(16)                   NOT NULL,
    payload      TEXT                          NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    confirmed_at TIMESTAMP(6) WITH TIME ZONE,
    expired_at   TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX idx_action_audit_student_status ON action_audit(student_id, status);
