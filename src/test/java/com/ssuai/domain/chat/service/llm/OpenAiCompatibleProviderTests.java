package com.ssuai.domain.chat.service.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.ssuai.domain.chat.config.LlmChatProperties;
import com.ssuai.domain.chat.dto.OpenAiChatCompletionRequest;

class OpenAiCompatibleProviderTests {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void modelFallbackTriesNextConfiguredModel() throws Exception {
        server.enqueue(rateLimitResponse());
        server.enqueue(successResponse("fallback"));
        LlmChatProperties properties = new LlmChatProperties();
        TestProvider provider = new TestProvider(
                properties,
                server.url("/openai/v1").toString(),
                List.of("model-1", "model-2")
        );

        LlmCompletionResult result = provider.complete(new LlmCompletionRequest(
                LlmPrivacyMode.PUBLIC,
                List.of(OpenAiChatCompletionRequest.userMessage("hello")),
                null,
                null
        ));

        assertThat(result.model()).isEqualTo("model-2");
        assertThat(result.message().content()).isEqualTo("fallback");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertRequest(server.takeRequest(), "model-1");
        assertRequest(server.takeRequest(), "model-2");
    }

    @Test
    void modelFallbackStopsAtConfiguredModelBudget() {
        server.enqueue(rateLimitResponse());
        server.enqueue(rateLimitResponse());
        LlmChatProperties properties = new LlmChatProperties();
        properties.setMaxModelsPerProvider(2);
        TestProvider provider = new TestProvider(
                properties,
                server.url("/openai/v1").toString(),
                List.of("model-1", "model-2", "model-3")
        );

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest(
                LlmPrivacyMode.PUBLIC,
                List.of(OpenAiChatCompletionRequest.userMessage("hello")),
                null,
                null
        )))
                .isInstanceOf(LlmProviderException.class);

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void directProviderUsesPrivateModelsForPrivateMode() throws Exception {
        server.enqueue(successResponse("private reply"));
        LlmChatProperties properties = new LlmChatProperties();
        LlmChatProperties.DirectProvider providerProperties = new LlmChatProperties.DirectProvider(
                server.url("/openai/v1").toString(),
                "test-key",
                List.of("public-model"),
                List.of("private-model")
        );
        TestDirectProvider provider = new TestDirectProvider(properties, providerProperties);

        LlmCompletionResult result = provider.complete(new LlmCompletionRequest(
                LlmPrivacyMode.PRIVATE,
                List.of(OpenAiChatCompletionRequest.userMessage("hello")),
                null,
                null
        ));

        assertThat(result.model()).isEqualTo("private-model");
        assertRequest(server.takeRequest(), "private-model");
    }

    @Test
    void mistralPrivateModelsAreSkippedUntilTrainingOptOutIsConfirmed() {
        LlmChatProperties properties = new LlmChatProperties();
        properties.getMistral().setBaseUrl(server.url("/mistral/v1").toString());
        properties.getMistral().setApiKey("test-key");
        properties.getMistral().setTrainingOptOutConfirmed(false);
        MistralLlmProvider provider = new MistralLlmProvider(properties, RestClient.builder());

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest(
                LlmPrivacyMode.PRIVATE,
                List.of(OpenAiChatCompletionRequest.userMessage("hello")),
                null,
                null
        )))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("No models configured");
        assertThat(server.getRequestCount()).isZero();
    }

    private static MockResponse rateLimitResponse() {
        return new MockResponse.Builder()
                .code(429)
                .setHeader("Content-Type", "application/json")
                .body("{\"error\":{\"message\":\"Rate limit exceeded\"}}")
                .build();
    }

    private static MockResponse successResponse(String content) {
        return new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""
                        {
                          "id": "response-id",
                          "model": "model-1",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "%s"
                              },
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """.formatted(content))
                .build();
    }

    private static void assertRequest(RecordedRequest request, String model) {
        assertThat(request.getTarget()).isEqualTo("/openai/v1/chat/completions");
        assertThat(request.getBody().utf8()).contains("\"model\":\"" + model + "\"");
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer test-key");
    }

    private static final class TestProvider extends OpenAiCompatibleProvider {

        private final String baseUrl;
        private final List<String> models;

        private TestProvider(LlmChatProperties properties, String baseUrl, List<String> models) {
            super("test", properties, RestClient.builder());
            this.baseUrl = baseUrl;
            this.models = models;
        }

        @Override
        protected String baseUrl() {
            return baseUrl;
        }

        @Override
        protected String apiKey() {
            return "test-key";
        }

        @Override
        protected List<String> models(LlmPrivacyMode privacyMode) {
            return models;
        }
    }

    private static final class TestDirectProvider extends DirectLlmProvider {

        private TestDirectProvider(
                LlmChatProperties properties,
                LlmChatProperties.DirectProvider providerProperties
        ) {
            super("test-direct", properties, RestClient.builder(), providerProperties);
        }
    }
}
