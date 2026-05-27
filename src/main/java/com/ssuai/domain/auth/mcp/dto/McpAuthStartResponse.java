package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;

/**
 * Response for {@code start_auth} MCP tool.
 *
 * <p>{@code loginUrl} points to the backend start endpoint which redirects to the
 * upstream SSO. The state token is embedded inside the URL; it is NOT a separate field
 * to avoid leaking it as a standalone value in the tool output.
 *
 * <p>{@code mcpSessionId} must be supplied in subsequent private tool calls.
 */
public record McpAuthStartResponse(
        String status,
        String provider,
        String mcpSessionId,
        String loginUrl,
        Instant expiresAt,
        String message) {
}
