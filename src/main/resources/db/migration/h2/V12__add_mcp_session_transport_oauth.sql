-- ADR 0036: opt-in 2-mode MCP auth (H2 dialect)
-- Mirrors postgresql/V12; H2 does not support partial indexes (WHERE clause),
-- so standard indexes are created instead.

ALTER TABLE mcp_sessions ADD COLUMN transport_session_id VARCHAR(128);
ALTER TABLE mcp_sessions ADD COLUMN oauth_subject       VARCHAR(255);

CREATE INDEX idx_mcp_sessions_transport
    ON mcp_sessions(transport_session_id);

CREATE INDEX idx_mcp_sessions_oauth_subject
    ON mcp_sessions(oauth_subject);
