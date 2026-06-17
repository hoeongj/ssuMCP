package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;

/**
 * Wrapper returned by all private MCP tools (get_my_schedule, get_my_grades,
 * get_my_assignments, get_library_seat_status, get_my_library_loans).
 *
 * <p>status=OK: {@code data} holds the payload; all other fields except mcpSessionId are null.
 * status=AUTH_REQUIRED: {@code loginUrl} and {@code provider} indicate what to do next;
 * {@code data} is null. The client should open {@code loginUrl} in a browser, then retry
 * the original private tool call with the same {@code mcpSessionId}.
 *
 * <p>Security: principalKey / studentId are never included in this response.
 */
public record McpPrivateToolResponse<T>(
        String status,
        String provider,
        String mcpSessionId,
        String loginUrl,
        Instant expiresAt,
        String message,
        T data) {

    public static <T> McpPrivateToolResponse<T> ok(String mcpSessionId, T data) {
        return new McpPrivateToolResponse<>("OK", null, mcpSessionId, null, null, null, data);
    }

    public static <T> McpPrivateToolResponse<T> authRequired(
            String mcpSessionId, String provider, String loginUrl, Instant expiresAt) {
        return new McpPrivateToolResponse<>(
                "AUTH_REQUIRED", provider, mcpSessionId, loginUrl, expiresAt,
                "AUTHENTICATION REQUIRED. Action needed: "
                        + "1) Open this exact raw loginUrl in a browser and complete login: " + loginUrl + " "
                        + "2) Show the same raw loginUrl as visible text to the user; do not replace it with a PlayMCP or connector page URL, and do not create a markdown link with a different target. "
                        + "3) After the user confirms login is complete, retry this exact tool call with mcp_session_id set to the mcpSessionId value shown here.",
                null);
    }

    public static <T> McpPrivateToolResponse<T> invalidSession(String mcpSessionId, String provider) {
        return new McpPrivateToolResponse<>(
                "INVALID_SESSION", provider, mcpSessionId, null, null,
                "SESSION NOT FOUND. The provided mcp_session_id does not match any active session "
                        + "(it may have expired). "
                        + "Call start_auth with the appropriate provider to begin a new session.",
                null);
    }
}
