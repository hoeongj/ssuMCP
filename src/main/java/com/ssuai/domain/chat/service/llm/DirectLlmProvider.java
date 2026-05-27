package com.ssuai.domain.chat.service.llm;

import java.util.List;

import org.springframework.web.client.RestClient;

import com.ssuai.domain.chat.config.LlmChatProperties;

class DirectLlmProvider extends OpenAiCompatibleProvider {

    private final LlmChatProperties.DirectProvider providerProperties;

    DirectLlmProvider(
            String name,
            LlmChatProperties chatProperties,
            RestClient.Builder restClientBuilder,
            LlmChatProperties.DirectProvider providerProperties
    ) {
        super(name, chatProperties, restClientBuilder);
        this.providerProperties = providerProperties;
    }

    @Override
    protected String baseUrl() {
        return providerProperties.getBaseUrl();
    }

    @Override
    protected String apiKey() {
        return providerProperties.getApiKey();
    }

    @Override
    protected List<String> models(LlmPrivacyMode privacyMode) {
        if (privacyMode == LlmPrivacyMode.PRIVATE) {
            return providerProperties.getPrivateModels();
        }
        return providerProperties.getPublicModels();
    }
}
