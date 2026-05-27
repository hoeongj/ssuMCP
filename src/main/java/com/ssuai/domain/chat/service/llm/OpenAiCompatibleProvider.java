package com.ssuai.domain.chat.service.llm;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.ssuai.domain.chat.config.LlmChatProperties;
import com.ssuai.domain.chat.dto.OpenAiChatCompletionRequest;
import com.ssuai.domain.chat.dto.OpenAiChatCompletionResponse;

abstract class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private final String name;
    private final LlmChatProperties properties;
    private final RestClient.Builder restClientBuilder;

    OpenAiCompatibleProvider(
            String name,
            LlmChatProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        this.name = name;
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isConfigured() {
        return hasApiKey();
    }

    @Override
    public LlmCompletionResult complete(LlmCompletionRequest request) {
        if (!hasApiKey()) {
            throw new LlmProviderException(name, "API key is not configured", true);
        }

        List<String> models = limitedModels(request.privacyMode());
        if (models.isEmpty()) {
            throw new LlmProviderException(name, "No models configured", true);
        }

        LlmProviderException lastFallbackFailure = null;
        for (String model : models) {
            try {
                OpenAiChatCompletionResponse response = completeModel(model, request);
                OpenAiChatCompletionResponse.Message message = response.firstMessage();
                if (message == null) {
                    throw new LlmProviderException(name, "Empty provider response", true);
                }
                return new LlmCompletionResult(name, model, message);
            } catch (LlmProviderException exception) {
                if (!exception.fallbackable()) {
                    throw exception;
                }

                lastFallbackFailure = exception;
                log.info("llm model fallback: provider={} model={} statusCode={} reason={}",
                        name, model, exception.statusCode(), fallbackReason(exception));
            }
        }

        throw lastFallbackFailure == null
                ? new LlmProviderException(name, "Provider exhausted", true)
                : lastFallbackFailure;
    }

    private OpenAiChatCompletionResponse completeModel(String model, LlmCompletionRequest request) {
        OpenAiChatCompletionRequest body = new OpenAiChatCompletionRequest(
                model,
                providerBody(request.privacyMode()),
                properties.getTemperature(),
                properties.getMaxTokens(),
                request.messages(),
                request.tools(),
                request.toolChoice()
        );

        try {
            OpenAiChatCompletionResponse response = restClient()
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers())
                    .body(body)
                    .retrieve()
                    .body(OpenAiChatCompletionResponse.class);
            if (response == null) {
                throw new LlmProviderException(name, "Provider returned empty response", true);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new LlmProviderException(
                    name,
                    "Provider request failed: status=" + exception.getStatusCode().value(),
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(),
                    shouldFallback(exception.getStatusCode().value(), exception.getResponseBodyAsString()),
                    exception);
        } catch (RestClientException exception) {
            throw new LlmProviderException(name, "Provider request failed", true);
        }
    }

    protected Object providerBody(LlmPrivacyMode privacyMode) {
        return null;
    }

    protected Consumer<HttpHeaders> headers() {
        return headers -> headers.setBearerAuth(apiKey());
    }

    private RestClient restClient() {
        return restClientBuilder
                .baseUrl(trimTrailingSlash(baseUrl()))
                .build();
    }

    private boolean hasApiKey() {
        return apiKey() != null && !apiKey().isBlank();
    }

    private boolean shouldFallback(int statusCode, String responseBody) {
        if (statusCode == 401 || statusCode == 402 || statusCode == 403 || statusCode == 404
                || statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500) {
            return true;
        }

        String body = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return body.contains("rate limit")
                || body.contains("ratelimit")
                || body.contains("quota")
                || body.contains("resource_exhausted")
                || body.contains("free limit")
                || body.contains("free-model")
                || body.contains("insufficient credits")
                || body.contains("no endpoints")
                || body.contains("model not found")
                || body.contains("unsupported")
                || body.contains("not supported");
    }

    private static String fallbackReason(LlmProviderException exception) {
        if (exception.statusCode() == 429) {
            return "rate_limit";
        }
        if (exception.statusCode() == 401 || exception.statusCode() == 403) {
            return "auth_or_permission";
        }
        if (exception.statusCode() == 402) {
            return "payment_or_credits";
        }
        if (exception.statusCode() >= 500) {
            return "server_error";
        }

        String body = exception.responseBody().toLowerCase(Locale.ROOT);
        if (body.contains("quota") || body.contains("resource_exhausted")) {
            return "quota";
        }
        if (body.contains("insufficient credits")) {
            return "insufficient_credits";
        }
        if (body.contains("free")) {
            return "free_limit";
        }
        if (body.contains("no endpoints")) {
            return "no_endpoints";
        }
        return "fallbackable_error";
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    protected abstract String baseUrl();

    protected abstract String apiKey();

    protected abstract List<String> models(LlmPrivacyMode privacyMode);

    private List<String> limitedModels(LlmPrivacyMode privacyMode) {
        List<String> configuredModels = models(privacyMode);
        int maxModels = Math.max(1, properties.getMaxModelsPerProvider());
        if (configuredModels.size() <= maxModels) {
            return configuredModels;
        }
        return configuredModels.subList(0, maxModels);
    }
}
