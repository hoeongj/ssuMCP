package com.ssuai.domain.chat.service;

import com.ssuai.domain.chat.dto.ChatResponse;

public interface ChatService {

    /**
     * Owner used when no explicit owner is threaded (test/convenience
     * overloads). Mirrors {@code ChatController}'s rule — authenticated
     * {@code studentId} when present, else a stable anonymous sentinel —
     * so conversation history stays scoped per identity even off the HTTP
     * path. Production always goes through {@link #reply(String, String,
     * String, String)} with the real server-session owner, so this
     * sentinel never reaches a live request.
     */
    String ANONYMOUS_OWNER = "anonymous";

    /**
     * Reply to an anonymous chat message. Equivalent to
     * {@link #reply(String, String, String)} with {@code studentId = null}
     * — private tools that require an authenticated student
     * ({@code get_my_schedule}, {@code get_my_grades}) refuse to run.
     */
    default ChatResponse reply(String conversationId, String message) {
        return reply(conversationId, message, null);
    }

    /**
     * Reply with the caller's authenticated student id available to
     * the private MCP tools. Pass {@code null} or blank to keep the
     * caller anonymous; the public tools (학식, 시설, 도서관 좌석…)
     * still work in that mode. The conversation owner is derived from
     * {@code studentId} (see {@link #ANONYMOUS_OWNER}); callers that need
     * an explicit owner (e.g. the HTTP server session for anonymous
     * users) use {@link #reply(String, String, String, String)}.
     */
    default ChatResponse reply(String conversationId, String message, String studentId) {
        return reply(ownerOf(studentId), conversationId, message, studentId);
    }

    /**
     * Canonical entry point. {@code owner} isolates conversation memory so
     * one caller can never read another caller's history by reusing or
     * guessing their {@code conversationId} (cross-user leak fix). The
     * controller passes the authenticated {@code studentId} when present,
     * else the server session id; {@code studentId} stays separate because
     * it gates the private MCP tools and may be {@code null}/blank even
     * when {@code owner} is a session id.
     */
    ChatResponse reply(String owner, String conversationId, String message, String studentId);

    /**
     * Derives the conversation owner from a student id for the convenience
     * overloads: the {@code studentId} when authenticated, else
     * {@link #ANONYMOUS_OWNER}.
     */
    static String ownerOf(String studentId) {
        return studentId != null && !studentId.isBlank() ? studentId : ANONYMOUS_OWNER;
    }
}
