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
     * Looks up a state token without consuming it. Returns the entry if the state is
     * valid and not expired. Use this before side-effectful operations so the state
     * can be retried if those operations fail.
     */
    Optional<McpAuthStateEntry> peekState(String state);

    /**
     * Atomically consumes a one-time state token. Returns the entry if the state is
     * valid and not expired. Returns empty on unknown, expired, or already-consumed state.
     */
    Optional<McpAuthStateEntry> consumeState(String state);

    /**
     * Returns the session whose {@code transport_session_id} matches, if active.
     * Fallback lookup path when the LLM drops the opaque session id (ADR 0036 §1B).
     */
    Optional<McpAuthSession> findByTransportId(String transportId);

    /**
     * Returns the session whose {@code oauth_subject} matches, if active.
     * Primary identity path when OAuth RS is enabled (ADR 0036 §1A).
     */
    Optional<McpAuthSession> findByOauthSubject(String oauthSubject);

    /**
     * Binds the HTTP-layer transport session id to the given session (idempotent).
     * Call on {@code start_auth} so that transport-based fallback works for all
     * subsequent private tool calls within the same connection.
     */
    void bindTransportId(McpAuthSessionId sessionId, String transportId);

    /**
     * Binds the OAuth {@code sub} to the given session (idempotent, opportunistic).
     * Call when a session is found via transport or opaque paths and a JWT is present,
     * so that future calls can find the session via the OAuth sub directly.
     */
    void bindOauthSubject(McpAuthSessionId sessionId, String oauthSubject);

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
