package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Objects;

/**
 * A one-time login state token stored in {@link McpAuthStateStore}.
 *
 * <p>{@code state} is a random opaque string included in the provider login URL.
 * The callback controller returns it to identify which MCP session completed login
 * and for which provider.
 *
 * <p>Once consumed by {@link McpAuthStateStore#consume}, the entry is removed and
 * cannot be reused (replay protection).
 */
public record McpAuthStateEntry(
        String state,
        McpAuthSessionId mcpSessionId,
        McpProviderType provider,
        Instant expiresAt) {

    public McpAuthStateEntry {
        Objects.requireNonNull(state, "state required");
        if (state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }
        Objects.requireNonNull(mcpSessionId, "mcpSessionId required");
        Objects.requireNonNull(provider, "provider required");
        Objects.requireNonNull(expiresAt, "expiresAt required");
    }
}
