package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;

/**
 * Wrapper returned by all private MCP tools (get_my_schedule, get_my_grades,
 * get_my_assignments, get_library_seat_status, get_my_library_loans, ...).
 *
 * <p>Two-audience messages (ADR 0045):
 * <ul>
 *   <li>{@code userMessage} — a short, friendly Korean line for the end user.</li>
 *   <li>{@code developerMessage} — verbose, agent/LLM-facing detail (procedure, codes, next steps).</li>
 *   <li>{@code message} — retained as a backward-compatible alias of {@code developerMessage}
 *       for existing MCP clients (ChatGPT, Claude Desktop) that already read it. Its bytes are
 *       intentionally unchanged; new clients/UIs should prefer {@code userMessage}.</li>
 * </ul>
 *
 * <p>status=OK: {@code data} holds the payload; message fields are null.
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
        String userMessage,
        String developerMessage,
        T data) {

    public static <T> McpPrivateToolResponse<T> ok(String mcpSessionId, T data) {
        return new McpPrivateToolResponse<>("OK", null, mcpSessionId, null, null, null, null, null, data);
    }

    public static <T> McpPrivateToolResponse<T> authRequired(
            String mcpSessionId, String provider, String loginUrl, Instant expiresAt) {
        // developerMessage is the verbose, agent-facing procedure. `message` keeps the exact
        // same bytes for backward compatibility with existing MCP clients.
        String developer = "AUTHENTICATION REQUIRED. Action needed: "
                + "1) Open this exact raw loginUrl in a browser and complete login: " + loginUrl + " "
                + "2) Show the same raw loginUrl as visible text to the user; do not replace it with a PlayMCP or connector page URL, and do not create a markdown link with a different target. "
                + "3) After the user confirms login is complete, retry this exact tool call with mcp_session_id set to the mcpSessionId value shown here.";
        String user = "로그인이 필요해요. 아래 링크를 브라우저에서 열어 로그인한 뒤 같은 요청을 다시 해주세요: " + loginUrl;
        return new McpPrivateToolResponse<>(
                "AUTH_REQUIRED", provider, mcpSessionId, loginUrl, expiresAt,
                developer, user, developer, null);
    }

    public static <T> McpPrivateToolResponse<T> invalidSession(String mcpSessionId, String provider) {
        String developer = "SESSION NOT FOUND. The provided mcp_session_id does not match any active session "
                + "(it may have expired). "
                + "Call start_auth with the appropriate provider to begin a new session.";
        String user = "세션이 만료됐거나 찾을 수 없어요. 다시 로그인해 주세요.";
        return new McpPrivateToolResponse<>(
                "INVALID_SESSION", provider, mcpSessionId, null, null,
                developer, user, developer, null);
    }
}
