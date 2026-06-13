package com.ssuai.domain.lms.video.util;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.ssuai.domain.lms.video.properties.LmsVideoProperties;

@Component
public class GroqSttClient {

    private static final Logger log = LoggerFactory.getLogger(GroqSttClient.class);

    private final LmsVideoProperties properties;
    private final RestClient restClient;

    public GroqSttClient(LmsVideoProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .baseUrl(trimTrailingSlash(properties.getGroqBaseUrl()))
                .build();
    }

    public boolean isUsable() {
        return properties.isGroqUsable();
    }

    /**
     * Transcribes one audio chunk. Returns empty text on any upstream failure.
     */
    public String transcribe(Path audioFile) {
        if (!isUsable()) {
            log.warn("groq-stt: API key not configured, returning empty");
            return "";
        }
        try {
            byte[] audioBytes = Files.readAllBytes(audioFile);
            String filename = audioFile.getFileName().toString();

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", properties.getSttModel());
            body.add("language", properties.getSttLanguage());
            body.add("response_format", "text");
            body.add("file", new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });

            String result = restClient.post()
                    .uri("/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(properties.getGroqApiKey()))
                    .body(body)
                    .retrieve()
                    .body(String.class);

            log.info("groq-stt: transcribed {} bytes -> {} chars",
                    audioBytes.length, result == null ? 0 : result.length());
            return result == null ? "" : result.trim();
        } catch (Exception exception) {
            log.warn("groq-stt: transcription failed: {}", exception.getMessage());
            return "";
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
