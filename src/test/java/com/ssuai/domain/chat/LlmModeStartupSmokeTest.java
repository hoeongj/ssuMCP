package com.ssuai.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.ssuai.domain.chat.dto.OpenAiChatCompletionResponse;
import com.ssuai.domain.chat.service.llm.LlmCompletionRequest;
import com.ssuai.domain.chat.service.llm.LlmCompletionResult;
import com.ssuai.domain.chat.service.llm.LlmProvider;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ssuai.connector.chat=llm",
        "ssuai.connector.meal=mock",
        "ssuai.connector.dorm-meal=mock",
        "ssuai.chat.llm.gemini.api-key=test-key-dummy",
        "ssuai.chat.llm.provider-order=test-fake",
        "ssuai.chat.llm.private-provider-order=test-fake",
        "spring.ai.mcp.client.enabled=true"
})
class LlmModeStartupSmokeTest {

    private static final int SERVER_PORT = findAvailablePort();

    private final ObjectMapper objectMapper;

    @LocalServerPort
    private int serverPort;

    @Autowired
    LlmModeStartupSmokeTest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @DynamicPropertySource
    static void mcpSelfConnectionProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> SERVER_PORT);
        registry.add("spring.ai.mcp.client.streamable-http.connections.self.url",
                () -> "http://localhost:" + SERVER_PORT);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void chatEndpointUsesFakeLlmProviderWithoutCallingExternalApi() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + "/api/chat"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"message":"오늘 학식 뭐야?"}
                        """))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = objectMapper.readTree(response.body());

        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(body.path("data").path("reply").asText()).isEqualTo("테스트 응답");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeProviderConfig {

        @Bean
        LlmProvider testFakeProvider() {
            return new LlmProvider() {
                @Override
                public String name() {
                    return "test-fake";
                }

                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public LlmCompletionResult complete(LlmCompletionRequest request) {
                    return new LlmCompletionResult(
                            "test-fake",
                            "test-fake-model",
                            new OpenAiChatCompletionResponse.Message("assistant", "테스트 응답", null)
                    );
                }
            };
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not reserve a test server port", exception);
        }
    }
}
