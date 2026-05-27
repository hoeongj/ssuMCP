package com.ssuai.domain.auth.mcp.dto;

/**
 * Response for {@code logout_provider} and {@code logout_all} MCP tools.
 * {@code provider} is null for logout_all.
 */
public record McpAuthLogoutResponse(String status, String mcpSessionId, String provider, String message) {

    public static McpAuthLogoutResponse providerLogout(String mcpSessionId, String provider) {
        return new McpAuthLogoutResponse("OK", mcpSessionId, provider,
                provider + " logged out. Call start_auth to re-authenticate.");
    }

    public static McpAuthLogoutResponse allLogout(String mcpSessionId) {
        return new McpAuthLogoutResponse("OK", mcpSessionId, null,
                "All providers logged out. MCP session invalidated.");
    }
}
