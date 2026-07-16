package com.ssuai.domain.auth.mcp;

import java.util.Optional;
import java.util.function.Supplier;

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
    boolean bindTransportId(McpAuthSessionId sessionId, String transportId);

    /**
     * Binds the OAuth {@code sub} to the given session (idempotent, opportunistic).
     * Call when a session is found via transport or opaque paths and a JWT is present,
     * so that future calls can find the session via the OAuth sub directly.
     */
    void bindOauthSubject(McpAuthSessionId sessionId, String oauthSubject);

    /**
     * Binds the OAuth {@code sub} when unbound, or verifies it matches an existing binding
     * (security hardening for tier-2/3 resolution). Returns {@code true} only when the
     * caller's identity provably owns the session; returns {@code false} on a mismatch,
     * blank arg, or missing session — the caller MUST treat {@code false} as "ownership
     * not confirmed" and deny the resolution.
     */
    boolean bindOrVerifyOauthSubject(McpAuthSessionId sessionId, String oauthSubject);

    /** Read-only ownership check used by ordinary resolution; never creates a binding. */
    boolean verifyOauthSubject(McpAuthSessionId sessionId, String oauthSubject);

    /**
     * Links a provider session to the MCP auth session identified by {@code sessionId}.
     * {@code principalKey} is the key used to look up credentials in the provider store
     * (normally an opaque credential-owner namespace; never infer it from web identity).
     */
    void linkProvider(McpAuthSessionId sessionId, McpProviderType provider, String principalKey);

    /** Commits a provider callback only if no logout or newer auth attempt superseded it. */
    boolean linkProviderIfCurrentAttempt(
            McpAuthSessionId sessionId,
            McpProviderType provider,
            String principalKey,
            long expectedRevision);

    /** True only while the exact live session still links this provider generation. */
    boolean ownsProviderCredential(
            String ownerMcpSessionId,
            McpProviderType provider,
            String credentialKey);

    /**
     * Executes an irreversible provider operation while holding the same live-session row
     * lock used by logout/unlink. The exact provider credential is revalidated under that
     * lock, which creates a deterministic ordering between revocation and the upstream write.
     *
     * @throws McpProviderCredentialRevokedException when the session/link/generation is no
     *         longer current; the supplied operation is never invoked in that case
     */
    <T> T executeWhileProviderCredentialCurrent(
            String ownerMcpSessionId,
            McpProviderType provider,
            String credentialKey,
            Supplier<T> operation);

    /**
     * Removes a single provider link from the session (per-provider logout).
     */
    void unlinkProvider(McpAuthSessionId sessionId, McpProviderType provider);

    /**
     * Atomically removes and returns the exact provider link observed under the session lock.
     * Revocation cleanup must use this value instead of a stale pre-lock session snapshot.
     */
    Optional<McpProviderLink> unlinkProviderAndGetLink(
            McpAuthSessionId sessionId, McpProviderType provider);

    /**
     * Removes the entire MCP auth session (logout all providers).
     */
    void invalidateSession(McpAuthSessionId sessionId);
}
