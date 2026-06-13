package com.ssuai.domain.lms.video.util;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ssuai.domain.lms.video.properties.LmsVideoProperties;

class GroqSttClientTests {

    private WireMockServer wireMockServer;
    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
        tempFile = Files.createTempFile("groq-stt-test-", ".mp3");
        Files.writeString(tempFile, "audio");
    }

    @AfterEach
    void tearDown() throws IOException {
        wireMockServer.stop();
        Files.deleteIfExists(tempFile);
    }

    @Test
    void transcribesAudioFile() {
        stubFor(post(urlEqualTo("/audio/transcriptions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/plain; charset=UTF-8")
                        .withBody("안녕하세요 이것은 테스트입니다")));

        GroqSttClient client = new GroqSttClient(properties("key"));

        assertThat(client.transcribe(tempFile)).isEqualTo("안녕하세요 이것은 테스트입니다");
    }

    @Test
    void returnsEmptyOnServerError() {
        stubFor(post(urlEqualTo("/audio/transcriptions"))
                .willReturn(aResponse().withStatus(500)));

        GroqSttClient client = new GroqSttClient(properties("key"));

        assertThat(client.transcribe(tempFile)).isEmpty();
    }

    @Test
    void blankApiKeyIsNotUsable() {
        GroqSttClient client = new GroqSttClient(properties(""));

        assertThat(client.isUsable()).isFalse();
    }

    private LmsVideoProperties properties(String apiKey) {
        LmsVideoProperties properties = new LmsVideoProperties();
        properties.setGroqBaseUrl("http://localhost:" + wireMockServer.port());
        properties.setGroqApiKey(apiKey);
        return properties;
    }
}
