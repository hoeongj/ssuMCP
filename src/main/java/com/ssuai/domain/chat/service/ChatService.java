package com.ssuai.domain.chat.service;

import com.ssuai.domain.chat.dto.ChatResponse;

public interface ChatService {

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
     * still work in that mode.
     */
    ChatResponse reply(String conversationId, String message, String studentId);
}
