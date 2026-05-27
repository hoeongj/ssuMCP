package com.ssuai.domain.chat.service.llm;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.ssuai.domain.chat.config.LlmChatProperties;

@Component
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "llm")
public class OpenRouterLlmProvider extends OpenAiCompatibleProvider {

    private final LlmChatProperties.OpenRouterProvider properties;

    public OpenRouterLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        super("openrouter", chatProperties, restClientBuilder);
        this.properties = chatProperties.getOpenrouter();
    }

    @Override
    protected String baseUrl() {
        return properties.getBaseUrl();
    }

    @Override
    protected String apiKey() {
        return properties.getApiKey();
    }

    @Override
    protected List<String> models(LlmPrivacyMode privacyMode) {
        if (privacyMode == LlmPrivacyMode.PRIVATE) {
            return properties.getPrivateModels();
        }
        return properties.getPublicModels();
    }

    @Override
    protected Object providerBody(LlmPrivacyMode privacyMode) {
        boolean requireZdr = privacyMode == LlmPrivacyMode.PRIVATE
                ? properties.isPrivateRequireZdr()
                : properties.isPublicRequireZdr();
        String dataCollection = privacyMode == LlmPrivacyMode.PRIVATE
                ? properties.getPrivateDataCollection()
                : properties.getPublicDataCollection();

        return new ProviderPreferences(
                dataCollection,
                requireZdr ? Boolean.TRUE : null,
                Boolean.TRUE,
                new MaxPrice(properties.getMaxPricePrompt(), properties.getMaxPriceCompletion())
        );
    }

    @Override
    protected java.util.function.Consumer<HttpHeaders> headers() {
        return headers -> {
            headers.setBearerAuth(apiKey());
            if (properties.getHttpReferer() != null && !properties.getHttpReferer().isBlank()) {
                headers.set("HTTP-Referer", properties.getHttpReferer());
            }
            if (properties.getAppTitle() != null && !properties.getAppTitle().isBlank()) {
                headers.set("X-Title", properties.getAppTitle());
            }
        };
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ProviderPreferences(
            @JsonProperty("data_collection")
            String dataCollection,
            Boolean zdr,
            @JsonProperty("require_parameters")
            Boolean requireParameters,
            @JsonProperty("max_price")
            MaxPrice maxPrice
    ) {
    }

    private record MaxPrice(
            BigDecimal prompt,
            BigDecimal completion
    ) {
    }
}
