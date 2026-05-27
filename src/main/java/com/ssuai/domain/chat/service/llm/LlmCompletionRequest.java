package com.ssuai.domain.chat.service.llm;

import java.util.List;

import com.ssuai.domain.chat.dto.OpenAiChatCompletionRequest;

public record LlmCompletionRequest(
        LlmPrivacyMode privacyMode,
        List<OpenAiChatCompletionRequest.Message> messages,
        List<OpenAiChatCompletionRequest.Tool> tools,
        Object toolChoice
) {
}
