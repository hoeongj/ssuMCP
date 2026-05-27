package com.ssuai.domain.chat.service.llm;

import com.ssuai.domain.chat.dto.OpenAiChatCompletionResponse;

public record LlmCompletionResult(
        String providerName,
        String model,
        OpenAiChatCompletionResponse.Message message
) {
}
