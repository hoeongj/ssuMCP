package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;

import org.slf4j.MDC;

/**
 * Wrapper returned by all private MCP tools (get_my_schedule, get_my_grades,
 * get_my_assignments, get_my_library_loans, recommend_library_seats, ...).
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
 * <p>status=OK: {@code data} holds the payload; message fields are null. {@code provider}
 * names the provider that served the response (SAINT/LMS/LIBRARY) and {@code mcpSessionId}
 * carries the exact explicit session id, or the valid current transport binding when the
 * explicit argument was omitted.
 * status=AUTH_REQUIRED: {@code loginUrl} and {@code provider} indicate what to do next;
 * {@code data} is null. The client should open {@code loginUrl} in a browser, then retry
 * the original private tool call with the same {@code mcpSessionId}. Denial responses for
 * invalid or mismatched identifiers deliberately omit session and login details.
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
        T data,
        String code,
        Boolean retryable,
        String correlationId) {

    public static <T> McpPrivateToolResponse<T> ok(String mcpSessionId, String provider, T data) {
        return response("OK", provider, mcpSessionId, null, null, null, null, data, false);
    }

    /**
     * A completed request with a machine-readable non-OK outcome.  This is used for safe
     * no-op and conflict results (for example, an already-consumed action), rather than
     * disguising them as {@code OK} strings.  The resolved session ID is retained only because
     * the request was authorised; callers must never use this factory for resolver failures.
     */
    public static <T> McpPrivateToolResponse<T> outcome(
            String code, String mcpSessionId, String provider, T data,
            String userMessage, String developerMessage, boolean retryable) {
        return response(code, provider, mcpSessionId, null, null,
                developerMessage, userMessage, data, retryable);
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
        return response("AUTH_REQUIRED", provider, mcpSessionId, loginUrl, expiresAt,
                developer, user, null, false);
    }

    public static <T> McpPrivateToolResponse<T> invalidSession(String provider) {
        String developer = "SESSION NOT FOUND. The provided mcp_session_id does not match any active session "
                + "(it may have expired). "
                + "Call start_auth with the appropriate provider to begin a new session.";
        String user = "세션이 만료됐거나 찾을 수 없어요. 다시 로그인해 주세요.";
        return response("INVALID_SESSION", provider, null, null, null,
                developer, user, null, false);
    }

    /** Backward-compatible factory signature; the untrusted id is intentionally not echoed. */
    @Deprecated
    public static <T> McpPrivateToolResponse<T> invalidSession(String ignoredSessionId, String provider) {
        return invalidSession(provider);
    }

    public static <T> McpPrivateToolResponse<T> noSession(String provider) {
        String developer = "NO SESSION. Call start_auth first, then retry with the returned mcpSessionId.";
        String user = "인증 세션이 없어요. 먼저 로그인을 시작해 주세요.";
        return response("NO_SESSION", provider, null, null, null,
                developer, user, null, false);
    }

    public static <T> McpPrivateToolResponse<T> sessionMismatch(String provider) {
        String developer = "SESSION MISMATCH. The supplied mcp_session_id differs from the current MCP request binding. "
                + "No session was selected and no data was accessed. Use start_auth to explicitly rebind.";
        String user = "요청 세션과 연결된 세션이 일치하지 않아요. 다시 인증해 주세요.";
        return response("SESSION_MISMATCH", provider, null, null, null,
                developer, user, null, false);
    }

    private static <T> McpPrivateToolResponse<T> response(
            String status,
            String provider,
            String mcpSessionId,
            String loginUrl,
            Instant expiresAt,
            String developerMessage,
            String userMessage,
            T data,
            boolean retryable) {
        return new McpPrivateToolResponse<>(
                status, provider, mcpSessionId, loginUrl, expiresAt,
                developerMessage, userMessage, developerMessage, data,
                status, retryable, MDC.get("requestId"));
    }
}
