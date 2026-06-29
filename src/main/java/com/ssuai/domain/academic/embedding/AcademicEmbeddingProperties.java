package com.ssuai.domain.academic.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the academic-policy hybrid RAG embedding layer.
 *
 * <p>Disabled by default: the corpus is served by lexical search until embeddings
 * are explicitly enabled by env. When disabled, or when the upstream embedding API
 * is unreachable, search silently degrades to lexical-only — the feature never
 * blocks the existing behaviour.
 *
 * <p>api-key/base-url default to the same Gemini values the chat layer already uses,
 * so no new credential is introduced.
 */
@Component
@ConfigurationProperties(prefix = "ssuai.academic-policy.embedding")
public class AcademicEmbeddingProperties {

    /** Master switch. false = lexical-only (current behaviour). */
    private boolean enabled = false;

    /** OpenAI-compatible base url (Gemini's /v1beta/openai by default). */
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";

    private String apiKey = "";

    private String model = "gemini-embedding-001";

    /** Matryoshka output dimensions. 768 keeps quality while cutting memory/cosine cost 4x vs 3072. */
    private int dimensions = 768;

    /**
     * Texts per embeddings request when embedding the corpus. Kept small because the
     * Gemini free tier limits embedding tokens per minute (~30k TPM): a 96-chunk batch
     * of 700-char Korean regulation text is ~67k tokens and 429'd the very first request
     * of every refresh, so the corpus never warmed (prod 2026-06-29). 8 chunks ≈ 6k
     * tokens per request, and at the 15s batch interval ≈ 22k tokens/min stays under the
     * limit while still warming the ~217-chunk corpus within a few scheduled refreshes.
     */
    private int batchSize = 8;

    /**
     * Pause between consecutive batch requests. The Gemini free tier allows only a
     * handful of embedding requests per minute; firing the corpus batches
     * back-to-back returned 429s for everything after the first (prod 2026-06-11).
     */
    private long batchIntervalMs = 15_000;

    /** Extra attempts per batch when the API answers 429 (0 = give up immediately). */
    private int retryMaxAttempts = 3;

    /** First retry backoff; doubles per attempt (30s → 60s → 120s by default). */
    private long retryBackoffMs = 30_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = trimmed(baseUrl);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = trimmed(apiKey);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = trimmed(model);
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getBatchIntervalMs() {
        return batchIntervalMs;
    }

    public void setBatchIntervalMs(long batchIntervalMs) {
        this.batchIntervalMs = batchIntervalMs;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Secrets created with {@code kubectl create secret --from-literal} or echo
     * without {@code -n} carry a trailing newline; an LF inside an Authorization
     * header is rejected by the JDK HttpClient before the request is even sent.
     * Trim at binding time so no consumer can build an invalid header.
     */
    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
