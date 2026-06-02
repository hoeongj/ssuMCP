package com.ssuai.domain.chat.dto;

public record ChatResponse(
        String conversationId,
        String reply,
        String model
) {
    /** Convenience constructor for responses that do not involve an LLM call. */
    public ChatResponse(String conversationId, String reply) {
        this(conversationId, reply, null);
    }
}
