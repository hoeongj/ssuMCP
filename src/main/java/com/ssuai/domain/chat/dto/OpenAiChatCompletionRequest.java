package com.ssuai.domain.chat.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatCompletionRequest(
        String model,
        Object provider,
        double temperature,
        @JsonProperty("max_tokens")
        int maxTokens,
        List<Message> messages,
        List<Tool> tools,
        @JsonProperty("tool_choice")
        Object toolChoice
) {

    public static Message systemMessage(String content) {
        return new Message("system", content, null, null);
    }

    public static Message userMessage(String content) {
        return new Message("user", content, null, null);
    }

    public static Message assistantMessage(String content) {
        return new Message("assistant", content, null, null);
    }

    public static Message assistantToolCallMessage(String content, List<OpenAiToolCall> toolCalls) {
        return new Message("assistant", content, toolCalls, null);
    }

    public static Message toolResultMessage(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(
            String role,
            String content,
            @JsonProperty("tool_calls")
            List<OpenAiToolCall> toolCalls,
            @JsonProperty("tool_call_id")
            String toolCallId
    ) {
    }

    public record Tool(
            String type,
            FunctionDefinition function
    ) {
    }

    public record FunctionDefinition(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }
}
