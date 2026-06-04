package com.ssuai.domain.chat.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import com.ssuai.domain.chat.config.LlmChatProperties;

@Configuration
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "llm")
class LlmProviderConfig {

    @Bean
    @ConditionalOnMissingBean
    RestClient.Builder llmRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "primaryObjectMapper")
    ObjectMapper primaryObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }

    @Bean
    LlmProvider geminiLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("gemini", chatProperties, restClientBuilder, chatProperties.getGemini());
    }

    @Bean
    LlmProvider groqLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("groq", chatProperties, restClientBuilder, chatProperties.getGroq());
    }

    @Bean
    LlmProvider cerebrasLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("cerebras", chatProperties, restClientBuilder, chatProperties.getCerebras());
    }

    @Bean
    LlmProvider deepInfraLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("deepinfra", chatProperties, restClientBuilder, chatProperties.getDeepinfra());
    }

    @Bean
    LlmProvider sambaNovaLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("sambanova", chatProperties, restClientBuilder, chatProperties.getSambanova());
    }

    @Bean
    LlmProvider nscaleLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("nscale", chatProperties, restClientBuilder, chatProperties.getNscale());
    }

    @Bean
    LlmProvider fireworksLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("fireworks", chatProperties, restClientBuilder, chatProperties.getFireworks());
    }

    @Bean
    LlmProvider huggingFaceLlmProvider(LlmChatProperties chatProperties, RestClient.Builder restClientBuilder) {
        return direct("huggingface", chatProperties, restClientBuilder, chatProperties.getHuggingface());
    }

    private static LlmProvider direct(
            String name,
            LlmChatProperties chatProperties,
            RestClient.Builder restClientBuilder,
            LlmChatProperties.DirectProvider providerProperties
    ) {
        return new DirectLlmProvider(name, chatProperties, restClientBuilder, providerProperties);
    }
}
