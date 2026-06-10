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

    /** Texts per embeddings request when embedding the corpus. */
    private int batchSize = 96;

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
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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

    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
