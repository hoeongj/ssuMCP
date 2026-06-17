-- ADR 0036: opt-in 2-mode MCP auth
-- transport_session_id: HTTP-layer session id (Mcp-Session-Id header).
--   Fallback lookup key when the LLM fails to carry mcp_session_id across turns
--   (e.g. ChatGPT turn-boundary loss). Bound on start_auth; NULL for old sessions.
-- oauth_subject: Google OAuth sub claim from the JWT Bearer token.
--   Primary lookup key in opt-in OAuth mode. Bound on first authenticated request.
--   NULL for sessions created in classic (non-OAuth) mode.

ALTER TABLE mcp_sessions ADD COLUMN transport_session_id VARCHAR(128);
ALTER TABLE mcp_sessions ADD COLUMN oauth_subject       VARCHAR(255);

-- Partial indexes: most rows will have NULL values; index only non-NULL rows
-- to keep the index small and avoid false positives on NULL equality.
CREATE INDEX idx_mcp_sessions_transport
    ON mcp_sessions(transport_session_id)
    WHERE transport_session_id IS NOT NULL;

CREATE INDEX idx_mcp_sessions_oauth_subject
    ON mcp_sessions(oauth_subject)
    WHERE oauth_subject IS NOT NULL;
