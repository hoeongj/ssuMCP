CREATE TABLE mcp_sessions (
    session_id   VARCHAR(64)                  PRIMARY KEY,
    created_at   TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    expires_at   TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    providers    TEXT                         NOT NULL DEFAULT '{}'
);

CREATE INDEX mcp_sessions_expires_at_idx ON mcp_sessions (expires_at);
