package com.ssuai.domain.auth.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One-time state token store for the MCP auth login flow.
 *
 * <p>When {@code start_auth} or a private tool triggers login, this store
 * generates a random state token that is embedded in the provider login URL
 * (e.g. {@code /api/mcp/auth/saint/start?state=...}). The SSO callback returns
 * the state, the store consumes it (remove-on-read), and the callback controller
 * links the provider session to the correct MCP auth session.
 *
 * <p>Security properties:
 * <ul>
 *   <li>State is 128-bit random (UUID v4 hex, no hyphens).
 *   <li>Each state can be consumed exactly once — replay attempts return empty.
 *   <li>Expired states are rejected on consume.
 *   <li>An LRU cap bounds memory regardless of inbound request rate.
 *   <li>The raw state is never logged; use the caller's session fingerprint instead.
 * </ul>
 */
@Component
public class McpAuthStateStore {

    private static final Logger log = LoggerFactory.getLogger(McpAuthStateStore.class);

    private final McpAuthProperties properties;
    private final Clock clock;
    private final Map<String, McpAuthStateEntry> entries;

    @Autowired
    public McpAuthStateStore(McpAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    McpAuthStateStore(McpAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        int cap = Math.max(1, properties.getMaxStates());
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, McpAuthStateEntry> eldest) {
                return size() > cap;
            }
        };
    }

    /**
     * Generates a new one-time state token tied to the given session and provider,
     * stores it, and returns the entry (which contains the state string and expiry).
     */
    public McpAuthStateEntry generate(McpAuthSessionId mcpSessionId, McpProviderType provider) {
        String state = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = clock.instant().plus(properties.getStateTtl());
        McpAuthStateEntry entry = new McpAuthStateEntry(state, mcpSessionId, provider, expiresAt);
        synchronized (entries) {
            entries.put(state, entry);
        }
        log.debug("mcp state generated session={} provider={}", mcpSessionId.fingerprint(), provider);
        return entry;
    }

    /**
     * Consumes the state token — removes it from the store and returns the entry if
     * it is present and not expired. Returns {@link Optional#empty()} if the state is
     * unknown, already consumed, or expired (replay and timeout protection).
     */
    public Optional<McpAuthStateEntry> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        synchronized (entries) {
            McpAuthStateEntry entry = entries.remove(state);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.expiresAt().isBefore(clock.instant())) {
                log.debug("mcp state expired provider={}", entry.provider());
                return Optional.empty();
            }
            return Optional.of(entry);
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
