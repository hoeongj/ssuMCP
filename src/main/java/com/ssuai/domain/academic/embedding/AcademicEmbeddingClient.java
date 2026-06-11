package com.ssuai.domain.academic.embedding;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls Gemini's OpenAI-compatible {@code /embeddings} endpoint.
 *
 * <p>Reuses the same OpenAI-compatible REST approach the chat layer
 * ({@code OpenAiCompatibleProvider}) already uses, so chat and embeddings share
 * one provider-agnostic integration story and one credential (SSUAI_GEMINI_API_KEY).
 *
 * <p>Returns embeddings normalized to unit length and truncated to the configured
 * dimensions. gemini-embedding-001 uses Matryoshka Representation Learning, so a
 * 768-prefix of the 3072 vector stays meaningful; truncating client-side and
 * re-normalizing guarantees a fixed dimension regardless of whether the
 * OpenAI-compat layer honours the {@code dimensions} request field.
 *
 * <p>Never throws to callers: on any failure it returns an empty list so the
 * caller degrades to lexical-only search.
 */
@Component
public class AcademicEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(AcademicEmbeddingClient.class);

    private final AcademicEmbeddingProperties properties;
    private final RestClient restClient;

    public AcademicEmbeddingClient(AcademicEmbeddingProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .build();
    }

    public boolean isUsable() {
        return properties.isUsable();
    }

    /** Embeds one query. Empty optional-like {@code float[0]} on failure. */
    public float[] embedQuery(String text) {
        List<float[]> result = embed(List.of(text == null ? "" : text));
        return result.isEmpty() ? new float[0] : result.getFirst();
    }

    /**
     * Embeds texts in batches. Returns an empty list if embeddings are disabled or
     * any batch fails — callers treat an empty result as "fall back to lexical".
     */
    public List<float[]> embed(List<String> texts) {
        if (!properties.isUsable() || texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
            try {
                EmbeddingResponse response = restClient.post()
                        .uri("/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(properties.getApiKey()))
                        .body(new EmbeddingRequest(properties.getModel(), batch, properties.getDimensions()))
                        .retrieve()
                        .body(EmbeddingResponse.class);
                if (response == null || response.data() == null || response.data().size() != batch.size()) {
                    log.warn("academic-embedding: unexpected response size; falling back to lexical");
                    return List.of();
                }
                for (EmbeddingResponse.Item item : response.data()) {
                    vectors.add(normalizeAndTruncate(item.embedding(), properties.getDimensions()));
                }
            } catch (RuntimeException exception) {
                // Catch wider than RestClientException: invalid header values (e.g. a
                // credential with a stray newline) surface as IllegalArgumentException
                // from the HTTP client and must degrade, not propagate. Log only the
                // exception class — messages can echo header contents (the secret).
                log.warn("academic-embedding: request failed ({}); falling back to lexical",
                        exception.getClass().getSimpleName());
                return List.of();
            }
        }
        return vectors;
    }

    static float[] normalizeAndTruncate(List<Double> raw, int dimensions) {
        if (raw == null || raw.isEmpty()) {
            return new float[0];
        }
        int size = Math.min(dimensions, raw.size());
        float[] vector = new float[size];
        double sumSquares = 0.0;
        for (int i = 0; i < size; i++) {
            float value = raw.get(i).floatValue();
            vector[i] = value;
            sumSquares += (double) value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < size; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    record EmbeddingRequest(String model, List<String> input, int dimensions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(List<Double> embedding, int index) {
        }
    }
}
