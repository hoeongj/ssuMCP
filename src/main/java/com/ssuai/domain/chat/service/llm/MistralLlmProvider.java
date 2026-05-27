package com.ssuai.domain.chat.service.llm;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.ssuai.domain.chat.config.LlmChatProperties;

@Component
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "llm")
public class MistralLlmProvider extends DirectLlmProvider {

    private final LlmChatProperties.MistralProvider properties;

    public MistralLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        super("mistral", chatProperties, restClientBuilder, chatProperties.getMistral());
        this.properties = chatProperties.getMistral();
    }

    @Override
    protected List<String> models(LlmPrivacyMode privacyMode) {
        if (privacyMode == LlmPrivacyMode.PRIVATE && !properties.isTrainingOptOutConfirmed()) {
            return List.of();
        }
        return super.models(privacyMode);
    }
}
