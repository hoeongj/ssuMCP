package com.ssuai.domain.auth.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent store of {@link McpAuthSession} objects, keyed by
 * {@link McpAuthSessionId#value()}.
 *
 * <p>The actual upstream credentials (PortalCookies, LmsCookies, Pyxis tokens)
 * are NOT stored here. This store only holds the {@code principalKey} that
 * indexes into each provider-specific credential store.
 */
@Component
public class McpAuthSessionStore {

    private static final Logger log = LoggerFactory.getLogger(McpAuthSessionStore.class);
    private static final TypeReference<Map<String, ProviderEntry>> PROVIDERS_TYPE =
            new TypeReference<>() {};

    private final McpSessionRepository repository;
    private final ObjectMapper objectMapper;
    private final McpAuthProperties properties;
    private final Clock clock;

    @Autowired
    public McpAuthSessionStore(
            McpSessionRepository repository,
            McpAuthProperties properties
    ) {
        this(repository, defaultObjectMapper(), properties, Clock.systemUTC());
    }

    McpAuthSessionStore(
            McpSessionRepository repository,
            ObjectMapper objectMapper,
            McpAuthProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates a new session with a fresh {@link McpAuthSessionId} and no linked providers. */
    @Transactional
    public McpAuthSession create() {
        McpAuthSessionId id = McpAuthSessionId.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getSessionTtl());
        McpSessionEntity entity = new McpSessionEntity(id.value(), now, expiresAt, "{}");
        repository.save(entity);
        log.debug("mcp session created id={}", id.fingerprint());
        return toSession(entity);
    }

    /**
     * Returns the session for the given id if it exists and has not expired.
     * Expired sessions are dropped on access.
     */
    @Transactional
    public Optional<McpAuthSession> find(McpAuthSessionId id) {
        if (id == null) {
            return Optional.empty();
        }
        return findByValue(id.value());
    }

    /** Convenience overload accepting the raw string value from a tool argument. */
    @Transactional
    public Optional<McpAuthSession> find(String idValue) {
        if (idValue == null || idValue.isBlank()) {
            return Optional.empty();
        }
        return findByValue(idValue);
    }

    private Optional<McpAuthSession> findByValue(String value) {
        Instant now = clock.instant();
        cleanupExpired(now);
        return repository.findBySessionIdAndExpiresAtAfter(value, now)
                .map(this::toSession);
    }

    /**
     * Links a provider to the session, replacing any prior link for the same provider.
     * A no-op if the session does not exist or has expired.
     */
    @Transactional
    public void linkProvider(McpAuthSessionId id, McpProviderType provider, String principalKey) {
        if (id == null || provider == null || principalKey == null || principalKey.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        repository.findBySessionIdAndExpiresAtAfter(id.value(), now)
                .ifPresent(entity -> {
                    Map<McpProviderType, McpProviderLink> updated = new EnumMap<>(McpProviderType.class);
                    updated.putAll(deserializeProviders(entity.getProviders()));
                    updated.put(provider, new McpProviderLink(provider, principalKey, now));
                    entity.setProviders(serializeProviders(updated));
                    repository.save(entity);
                    log.debug("mcp provider linked session={} provider={}", id.fingerprint(), provider);
                });
    }

    /**
     * Removes the provider link from the session.
     * A no-op if the session or provider does not exist.
     */
    @Transactional
    public void unlinkProvider(McpAuthSessionId id, McpProviderType provider) {
        if (id == null || provider == null) {
            return;
        }
        repository.findById(id.value()).ifPresent(entity -> {
            Map<McpProviderType, McpProviderLink> updated = new EnumMap<>(McpProviderType.class);
            updated.putAll(deserializeProviders(entity.getProviders()));
            updated.remove(provider);
            entity.setProviders(serializeProviders(updated));
            repository.save(entity);
            log.debug("mcp provider unlinked session={} provider={}", id.fingerprint(), provider);
        });
    }

    /** Removes the entire session (logout all). */
    @Transactional
    public void invalidate(McpAuthSessionId id) {
        if (id == null) {
            return;
        }
        repository.deleteById(id.value());
        log.debug("mcp session invalidated id={}", id.fingerprint());
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanupExpiredSessions() {
        cleanupExpired(clock.instant());
    }

    int size() {
        cleanupExpired(clock.instant());
        return Math.toIntExact(repository.count());
    }

    private void cleanupExpired(Instant now) {
        int deleted = repository.deleteByExpiresAtBefore(now);
        if (deleted > 0) {
            log.debug("mcp sessions expired count={}", deleted);
        }
    }

    private McpAuthSession toSession(McpSessionEntity entity) {
        return new McpAuthSession(
                new McpAuthSessionId(entity.getSessionId()),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                deserializeProviders(entity.getProviders())
        );
    }

    private Map<McpProviderType, McpProviderLink> deserializeProviders(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        Map<String, ProviderEntry> raw;
        try {
            raw = objectMapper.readValue(rawJson, PROVIDERS_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse MCP session providers JSON", exception);
        }

        Map<McpProviderType, McpProviderLink> providers = new EnumMap<>(McpProviderType.class);
        for (Map.Entry<String, ProviderEntry> entry : raw.entrySet()) {
            McpProviderType provider = McpProviderType.valueOf(entry.getKey());
            ProviderEntry providerEntry = entry.getValue();
            providers.put(provider, new McpProviderLink(
                    provider,
                    providerEntry.principalKey(),
                    providerEntry.linkedAt()
            ));
        }
        return Map.copyOf(providers);
    }

    private String serializeProviders(Map<McpProviderType, McpProviderLink> providers) {
        Map<String, ProviderEntry> raw = new LinkedHashMap<>();
        providers.forEach((provider, link) -> raw.put(
                provider.name(),
                new ProviderEntry(link.principalKey(), link.linkedAt())
        ));
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize MCP session providers JSON", exception);
        }
    }

    static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }

    private record ProviderEntry(String principalKey, Instant linkedAt) {
    }
}
