package com.ssuai.domain.chat.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.chat.config.LlmChatProperties;
import com.ssuai.domain.chat.service.llm.LlmCompletionRequest;
import com.ssuai.domain.chat.service.llm.LlmCompletionResult;
import com.ssuai.domain.chat.service.llm.LlmPrivacyMode;
import com.ssuai.domain.chat.service.llm.LlmProvider;
import com.ssuai.domain.chat.service.llm.LlmProviderException;
import com.ssuai.global.admin.AdminResilienceResponse;
import com.ssuai.global.exception.ChatUnavailableException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "llm")
public class LlmProviderChain {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderChain.class);

    private final Map<String, LlmProvider> providersByName;
    private final LlmChatProperties properties;
    private final LlmProviderCbRegistry circuitBreakers;

    @Autowired
    public LlmProviderChain(
            Map<String, LlmProvider> providersByName,
            LlmChatProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.providersByName = providersByName.values()
                .stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::name, Function.identity()));
        this.properties = properties;
        this.circuitBreakers = new LlmProviderCbRegistry(meterRegistry);
    }

    LlmProviderChain(List<LlmProvider> providers, LlmChatProperties properties) {
        this(providers.stream().collect(Collectors.toUnmodifiableMap(LlmProvider::name, Function.identity())),
                properties,
                new SimpleMeterRegistry());
    }

    public LlmCompletionResult complete(LlmCompletionRequest request) {
        List<ProviderAttempt> attempts = providerAttempts(request.privacyMode());
        if (attempts.isEmpty()) {
            throw new ChatUnavailableException();
        }

        LlmProviderException lastFailure = null;
        int totalPasses = Math.max(1, properties.getAvailabilityVerificationPasses() + 1);
        for (int pass = 1; pass <= totalPasses; pass++) {
            for (ProviderAttempt attempt : attempts) {
                CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(attempt.provider().name());
                try {
                    return circuitBreaker.executeSupplier(() ->
                            attempt.provider().complete(withPrivacyMode(request, attempt.privacyMode())));
                } catch (CallNotPermittedException exception) {
                    log.info("llm provider short-circuited: provider={} privacyMode={} pass={} state={}",
                            attempt.provider().name(), attempt.privacyMode(), pass, circuitBreaker.getState());
                } catch (LlmProviderException exception) {
                    if (!exception.fallbackable()) {
                        throw new ChatUnavailableException(exception);
                    }
                    lastFailure = exception;
                    log.info("llm provider fallback: provider={} privacyMode={} pass={} statusCode={}",
                            exception.providerName(), attempt.privacyMode(), pass, exception.statusCode());
                }
            }
        }

        throw new ChatUnavailableException(lastFailure);
    }

    private List<ProviderAttempt> providerAttempts(LlmPrivacyMode privacyMode) {
        if (privacyMode == LlmPrivacyMode.PRIVATE) {
            return capProviderAttempts(orderedProviders(properties.getPrivateProviderOrder()).stream()
                    .map(provider -> new ProviderAttempt(provider, LlmPrivacyMode.PRIVATE))
                    .toList());
        }

        List<ProviderAttempt> attempts = new ArrayList<>();
        orderedProviders(properties.getProviderOrder()).stream()
                .map(provider -> new ProviderAttempt(provider, LlmPrivacyMode.PUBLIC))
                .forEach(attempts::add);
        orderedProviders(properties.getPrivateProviderOrder()).stream()
                .map(provider -> new ProviderAttempt(provider, LlmPrivacyMode.PRIVATE))
                .forEach(attempts::add);
        return capProviderAttempts(attempts);
    }

    private List<LlmProvider> orderedProviders(List<String> providerOrder) {
        if (providerOrder == null || providerOrder.isEmpty()) {
            return providersByName.values().stream()
                    .filter(LlmProvider::isConfigured)
                    .filter(provider -> !isCbOpen(provider.name()))
                    .toList();
        }

        return providerOrder.stream()
                .map(providersByName::get)
                .filter(provider -> provider != null && provider.isConfigured())
                .filter(provider -> !isCbOpen(provider.name()))
                .toList();
    }

    private List<ProviderAttempt> capProviderAttempts(List<ProviderAttempt> attempts) {
        int maxAttempts = Math.max(1, properties.getMaxProviderAttempts());
        if (attempts.size() <= maxAttempts) {
            return attempts;
        }
        return attempts.subList(0, maxAttempts);
    }

    private boolean isCbOpen(String providerName) {
        CircuitBreaker.State state = circuitBreakers.circuitBreaker(providerName).getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    static LlmCompletionRequest withPrivacyMode(
            LlmCompletionRequest request,
            LlmPrivacyMode privacyMode
    ) {
        if (request.privacyMode() == privacyMode) {
            return request;
        }
        return new LlmCompletionRequest(
                privacyMode,
                request.messages(),
                request.tools(),
                request.toolChoice()
        );
    }

    public List<AdminResilienceResponse.CircuitBreakerInfo> circuitBreakerStates() {
        return circuitBreakers.allCircuitBreakerStates();
    }

    record ProviderAttempt(
            LlmProvider provider,
            LlmPrivacyMode privacyMode
    ) {
    }
}

class LlmProviderCbRegistry {

    private static final String NAME_PREFIX = "llm-";

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    LlmProviderCbRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);
    }

    CircuitBreaker circuitBreaker(String providerName) {
        return circuitBreakerRegistry.circuitBreaker(NAME_PREFIX + providerName);
    }

    List<AdminResilienceResponse.CircuitBreakerInfo> allCircuitBreakerStates() {
        return circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .map(cb -> new AdminResilienceResponse.CircuitBreakerInfo(
                        cb.getName(),
                        cb.getState().name(),
                        cb.getMetrics().getFailureRate(),
                        cb.getMetrics().getSlowCallRate()))
                .sorted(java.util.Comparator.comparing(AdminResilienceResponse.CircuitBreakerInfo::name))
                .toList();
    }
}
