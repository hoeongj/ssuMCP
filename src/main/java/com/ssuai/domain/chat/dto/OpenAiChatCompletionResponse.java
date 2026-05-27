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
            int index,
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
