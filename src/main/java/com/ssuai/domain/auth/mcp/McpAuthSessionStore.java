package com.ssuai.domain.auth.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory store of {@link McpAuthSession} objects, keyed by
 * {@link McpAuthSessionId#value()}.
 *
 * <p>Supports TTL (absolute, checked on read) and LRU eviction when the cap is
 * exceeded. Sessions are immutable records; provider link mutations replace the
 * entire record atomically under the store's monitor.
 *
 * <p>The actual upstream credentials (PortalCookies, LmsCookies, Pyxis tokens) are
 * NOT stored here — they remain in their respective provider stores (SaintSessionStore,
 * LmsSessionStore, LibrarySessionStore). This store only holds the {@code principalKey}
 * that indexes into those stores.
 */
@Component
public class McpAuthSessionStore {

    private static final Logger log = LoggerFactory.getLogger(McpAuthSessionStore.class);

    private final McpAuthProperties properties;
    private final Clock clock;
    private final Map<String, McpAuthSession> sessions;

    @Autowired
    public McpAuthSessionStore(McpAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    McpAuthSessionStore(McpAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        int cap = Math.max(1, properties.getMaxSessions());
        this.sessions = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, McpAuthSession> eldest) {
                return size() > cap;
            }
        };
    }

    /** Creates a new session with a fresh {@link McpAuthSessionId} and no linked providers. */
    public McpAuthSession create() {
        McpAuthSessionId id = McpAuthSessionId.generate();
        Instant now = clock.instant();
        McpAuthSession session = new McpAuthSession(id, now, now.plus(properties.getSessionTtl()), Map.of());
        synchronized (sessions) {
            sessions.put(id.value(), session);
        }
        log.debug("mcp session created id={}", id.fingerprint());
        return session;
    }

    /**
     * Returns the session for the given id if it exists and has not expired.
     * Expired sessions are dropped on access.
     */
    public Optional<McpAuthSession> find(McpAuthSessionId id) {
        if (id == null) {
            return Optional.empty();
        }
        return findByValue(id.value());
    }

    /** Convenience overload accepting the raw string value from a tool argument. */
    public Optional<McpAuthSession> find(String idValue) {
        if (idValue == null || idValue.isBlank()) {
            return Optional.empty();
        }
        return findByValue(idValue);
    }

    private Optional<McpAuthSession> findByValue(String value) {
        synchronized (sessions) {
            McpAuthSession session = sessions.get(value);
            if (session == null) {
                return Optional.empty();
            }
            if (session.expiresAt().isBefore(clock.instant())) {
                sessions.remove(value);
                log.debug("mcp session expired id={}", session.id().fingerprint());
                return Optional.empty();
            }
            return Optional.of(session);
        }
    }

    /**
     * Links a provider to the session, replacing any prior link for the same provider.
     * A no-op if the session does not exist or has expired.
     */
    public void linkProvider(McpAuthSessionId id, McpProviderType provider, String principalKey) {
        if (id == null || provider == null || principalKey == null || principalKey.isBlank()) {
            return;
        }
        synchronized (sessions) {
            McpAuthSession existing = sessions.get(id.value());
            if (existing == null || existing.expiresAt().isBefore(clock.instant())) {
                return;
            }
            Map<McpProviderType, McpProviderLink> updated = new EnumMap<>(McpProviderType.class);
            updated.putAll(existing.providers());
            updated.put(provider, new McpProviderLink(provider, principalKey, clock.instant()));
            sessions.put(id.value(), existing.withProviders(Map.copyOf(updated)));
            log.debug("mcp provider linked session={} provider={}", id.fingerprint(), provider);
        }
    }

    /**
     * Removes the provider link from the session.
     * A no-op if the session or provider does not exist.
     */
    public void unlinkProvider(McpAuthSessionId id, McpProviderType provider) {
        if (id == null || provider == null) {
            return;
        }
        synchronized (sessions) {
            McpAuthSession existing = sessions.get(id.value());
            if (existing == null) {
                return;
            }
            Map<McpProviderType, McpProviderLink> updated = new EnumMap<>(McpProviderType.class);
            updated.putAll(existing.providers());
            updated.remove(provider);
            sessions.put(id.value(), existing.withProviders(Map.copyOf(updated)));
            log.debug("mcp provider unlinked session={} provider={}", id.fingerprint(), provider);
        }
    }

    /** Removes the entire session (logout all). */
    public void invalidate(McpAuthSessionId id) {
        if (id == null) {
            return;
        }
        synchronized (sessions) {
            sessions.remove(id.value());
        }
        log.debug("mcp session invalidated id={}", id.fingerprint());
    }

    int size() {
        synchronized (sessions) {
            return sessions.size();
        }
    }
}
