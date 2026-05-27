package com.ssuai.domain.auth.mcp;

import java.util.Optional;

/**
 * Central service for MCP auth session lifecycle.
 *
 * <p>Coordinates session creation, state token generation, provider linking, and
 * provider lookup. Does not know about provider-specific upstream stores (SaintSessionStore,
 * LibrarySessionStore, etc.) — those integrations happen in the callback controllers
 * and private tool layer (Slice B / C).
 */
public interface McpAuthService {

    /**
     * Returns the session for {@code idValue} if it exists and is valid.
     * Returns empty if the id is unknown, blank, or the session has expired.
     */
    Optional<McpAuthSession> find(String idValue);

    /**
     * Returns the existing session if valid, or creates a new one if no valid session
     * is found for {@code idValue}. If {@code idValue} is blank or null, always creates
     * a new session.
     */
    McpAuthSession getOrCreate(String idValue);

    /**
     * Creates a fresh MCP auth session without trying to resolve a caller-supplied
     * session id first. Use this when the caller supplied no session id at all.
     */
    McpAuthSession createSession();

    /**
     * Generates a one-time login state token for the given session and provider.
     * The state is stored and will be consumed by the callback controller.
     */
    McpAuthStateEntry generateState(McpAuthSessionId sessionId, McpProviderType provider);

    /**
     * Atomically consumes a one-time state token. Returns the entry if the state is
     * valid and not expired. Returns empty on unknown, expired, or already-consumed state.
     */
    Optional<McpAuthStateEntry> consumeState(String state);

    /**
     * Links a provider session to the MCP auth session identified by {@code sessionId}.
     * {@code principalKey} is the key used to look up credentials in the provider store
     * (studentId for SAINT/LMS, library session key for LIBRARY).
     */
    void linkProvider(McpAuthSessionId sessionId, McpProviderType provider, String principalKey);

    /**
     * Removes a single provider link from the session (per-provider logout).
     */
    void unlinkProvider(McpAuthSessionId sessionId, McpProviderType provider);

    /**
     * Removes the entire MCP auth session (logout all providers).
     */
    void invalidateSession(McpAuthSessionId sessionId);
}
