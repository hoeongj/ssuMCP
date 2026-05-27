package com.ssuai.domain.chat.service.llm;

public interface LlmProvider {

    String name();

    default boolean isConfigured() {
        return true;
    }

    LlmCompletionResult complete(LlmCompletionRequest request);
}
