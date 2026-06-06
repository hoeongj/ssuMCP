CREATE TABLE library_sessions (
    session_key  VARCHAR(255)                 PRIMARY KEY,
    iv_b64       VARCHAR(64)                  NOT NULL,
    cipher_b64   TEXT                         NOT NULL,
    captured_at  TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    expires_at   TIMESTAMP(6) WITH TIME ZONE  NOT NULL
);

CREATE INDEX idx_library_sessions_expires ON library_sessions (expires_at);
