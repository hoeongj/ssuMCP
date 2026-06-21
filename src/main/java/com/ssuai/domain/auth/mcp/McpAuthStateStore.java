package com.ssuai.domain.auth.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-backed one-time state token store for the MCP auth login flow.
 *
 * <p>States survive pod restarts because they are stored in the {@code mcp_auth_states}
 * table (V6 migration). The previous in-memory implementation lost all pending states
 * on every deployment, causing spurious INVALID_STATE errors during the login flow.
 *
 * <p>Security properties:
 * <ul>
 *   <li>State is 128-bit random (UUID v4 hex, no hyphens).
 *   <li>Each state can be consumed exactly once — replay attempts return empty.
 *   <li>Expired states are rejected on consume.
 *   <li>The raw state is never logged; use the caller's session fingerprint instead.
 * </ul>
 */
@Component
public class McpAuthStateStore {

    private static final Logger log = LoggerFactory.getLogger(McpAuthStateStore.class);

    private final McpAuthStateRepository repository;
    private final McpAuthProperties properties;
    private final Clock clock;

    @Autowired
    public McpAuthStateStore(McpAuthStateRepository repository, McpAuthProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    McpAuthStateStore(McpAuthStateRepository repository, McpAuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Generates a new one-time state token tied to the given session and provider,
     * persists it to Postgres, and returns the entry.
     */
    @Transactional
    public McpAuthStateEntry generate(McpAuthSessionId mcpSessionId, McpProviderType provider) {
        String state = UUID.randomUUID().toString().replace("-", "");
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getStateTtl());
        repository.save(new McpAuthStateEntity(state, mcpSessionId.value(), provider.name(), expiresAt, now));
        log.debug("mcp state generated session={} provider={}", mcpSessionId.fingerprint(), provider);
        return new McpAuthStateEntry(state, mcpSessionId, provider, expiresAt);
    }

    /**
     * Looks up the state without consuming it. Returns the entry if it exists and has not expired.
     * Use this to validate state before performing side-effectful operations (e.g. credential check).
     */
    @Transactional(readOnly = true)
    public Optional<McpAuthStateEntry> peek(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        return repository.findByStateAndExpiresAtAfter(state, clock.instant())
                .map(this::toEntry);
    }

    /**
     * Consumes the state token — atomically deletes it from the store and returns the entry
     * if it is present and not expired. Returns {@link Optional#empty()} if the state is
     * unknown, already consumed, or expired (replay and timeout protection).
     *
     * <p>The claim is made atomic via {@link McpAuthStateRepository#deleteIfActive}: the entry
     * is returned only when this caller's delete actually removed the row ({@code claimed == 1}).
     * If two concurrent callbacks present the same state, exactly one deletes the row and wins;
     * the loser sees {@code claimed == 0} and gets empty, even though its earlier {@code find}
     * had observed the (still-present) row. This closes the find-then-delete race.
     */
    @Transactional
    public Optional<McpAuthStateEntry> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<McpAuthStateEntity> found = repository.findByStateAndExpiresAtAfter(state, now);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int claimed = repository.deleteIfActive(state, now);
        return claimed == 1 ? found.map(this::toEntry) : Optional.empty();
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanupExpiredStates() {
        int deleted = repository.deleteByExpiresAtBefore(clock.instant());
        if (deleted > 0) {
            log.debug("mcp auth states expired count={}", deleted);
        }
    }

    int size() {
        return Math.toIntExact(repository.count());
    }

    private McpAuthStateEntry toEntry(McpAuthStateEntity entity) {
        return new McpAuthStateEntry(
                entity.getState(),
                new McpAuthSessionId(entity.getSessionId()),
                McpProviderType.valueOf(entity.getProvider()),
                entity.getExpiresAt()
        );
    }
}
