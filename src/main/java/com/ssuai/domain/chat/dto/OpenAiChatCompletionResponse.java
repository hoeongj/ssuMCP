package com.ssuai.domain.chat.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatCompletionResponse(
        String id,
        String model,
        List<Choice> choices
) {

    public Message firstMessage() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getFirst().message();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            // No "index": it is unused (only message() is read) and OpenAI-compatible
            // providers omit it for the first choice (index 0), exactly as Gemini omits
            // the first embedding item's index. A primitive `int index` made Jackson 3
            // throw MismatchedInputException and discard the whole chat response, breaking
            // chat for any such provider — the same latent bug fixed for embeddings
            // (TROUBLESHOOTING 사건 22, 2026-06-29).
            Message message,
            @JsonProperty("finish_reason")
            String finishReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String role,
            String content,
            @JsonProperty("tool_calls")
            List<OpenAiToolCall> toolCalls
    ) {
    }
}
