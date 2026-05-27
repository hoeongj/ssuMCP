package com.ssuai.domain.auth.mcp.dto;

import java.util.List;

/**
 * Response for {@code get_auth_status} MCP tool.
 *
 * <p>{@code mcpSessionId} is the session handle the client should reuse in subsequent
 * private tool calls. {@code providers} lists all known providers and their link status.
 * Student id / principalKey is never included.
 */
public record McpAuthStatusResponse(
        String status,
        String mcpSessionId,
        List<McpProviderStatusEntry> providers) {

    public McpAuthStatusResponse(String mcpSessionId, List<McpProviderStatusEntry> providers) {
        this("OK", mcpSessionId, providers);
    }
}
