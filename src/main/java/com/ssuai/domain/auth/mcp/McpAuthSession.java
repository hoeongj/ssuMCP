package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An MCP auth session: a container for one external MCP client's linked provider
 * sessions (SAINT, LMS, LIBRARY).
 *
 * <p>Instances are immutable. {@link McpAuthSessionStore} replaces the whole
 * record atomically when a provider is linked or unlinked.
 *
 * <p>{@code providers} is always an unmodifiable map. Use {@link #withProviders}
 * to derive an updated copy.
 */
public record McpAuthSession(
        McpAuthSessionId id,
        Instant createdAt,
        Instant expiresAt,
        Map<McpProviderType, McpProviderLink> providers) {

    public McpAuthSession {
        Objects.requireNonNull(id, "id required");
        Objects.requireNonNull(createdAt, "createdAt required");
        Objects.requireNonNull(expiresAt, "expiresAt required");
        Objects.requireNonNull(providers, "providers required");
        providers = Map.copyOf(providers);
    }

    public Optional<McpProviderLink> provider(McpProviderType type) {
        return Optional.ofNullable(providers.get(type));
    }

    public boolean isLinked(McpProviderType type) {
        return providers.containsKey(type);
    }

    /** Returns a new session with the given providers map substituted in. */
    public McpAuthSession withProviders(Map<McpProviderType, McpProviderLink> updated) {
        return new McpAuthSession(id, createdAt, expiresAt, updated);
    }
}
