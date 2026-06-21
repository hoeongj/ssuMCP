package com.ssuai.domain.mcp.tool;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthStateEntry;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderLink;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;

/**
 * Helper shared by all private MCP tools. Resolves the session using a 3-tier
 * principal lookup strategy (ADR 0036) and builds AUTH_REQUIRED responses.
 *
 * <h2>3-tier session resolution (ADR 0036)</h2>
 * <ol>
 *   <li><b>OAuth {@code sub}</b> — extracted from a verified Bearer JWT in
 *       {@code SecurityContext}. Stable across all LLM turns and connections
 *       because it lives in the HTTP layer, not in LLM memory. Used only when
 *       {@code rs-enabled=true}; returns null in classic mode.</li>
 *   <li><b>Transport session id</b> ({@code Mcp-Session-Id} request header) —
 *       assigned by the MCP server per connection. Stable within one connection
 *       even when the LLM drops its opaque session id across turns (e.g. ChatGPT
 *       turn-boundary drop). Bound to the auth session by {@code start_auth}.</li>
 *   <li><b>Opaque {@code mcp_session_id}</b> — carried in LLM tool arguments.
 *       May be dropped; only used as a last resort.</li>
 * </ol>
 *
 * <h2>Opportunistic binding</h2>
 * <p>When a session is found via tier 2 or 3 and a JWT {@code sub} is present,
 * the sub is bound to the session so future calls can find it via tier 1 without
 * needing the transport or opaque id. Similarly, when found via tier 3 and a
 * transport id is available, it is bound for tier-2 future lookups.</p>
 */
@Component
public class McpAuthHelper {

    private static final Logger log = LoggerFactory.getLogger(McpAuthHelper.class);

    private final McpAuthService mcpAuthService;
    private final McpAuthUrlFactory urlFactory;
    private final HttpServletRequest request;

    public McpAuthHelper(
            McpAuthService mcpAuthService,
            McpAuthUrlFactory urlFactory,
            HttpServletRequest request
    ) {
        this.mcpAuthService = mcpAuthService;
        this.urlFactory = urlFactory;
        this.request = request;
    }

    /**
     * Resolves the auth session using the 3-tier strategy. Performs opportunistic
     * binding when the session is found via a lower-priority tier.
     *
     * @param mcpSessionId opaque session id from the LLM tool argument (may be null)
     * @return the resolved session, or empty if no valid session is found via any tier
     */
    public Optional<McpAuthSession> resolveSession(String mcpSessionId) {
        String oauthSub = currentOauthSub();
        String transportId = currentTransportId();

        // Tier 1: OAuth sub (most stable — HTTP-layer identity)
        if (oauthSub != null) {
            Optional<McpAuthSession> session = mcpAuthService.findByOauthSubject(oauthSub);
            if (session.isPresent()) {
                log.debug("mcp session resolved via oauth-sub session={}",
                        session.get().id().fingerprint());
                return session;
            }
        }

        // Tier 2: transport session id (connection-stable, survives LLM turn drops)
        if (transportId != null) {
            Optional<McpAuthSession> session = mcpAuthService.findByTransportId(transportId);
            if (session.isPresent()) {
                McpAuthSession found = session.get();
                log.debug("mcp session resolved via transport session={}", found.id().fingerprint());
                // Opportunistic: bind oauth sub for future tier-1 lookup
                if (oauthSub != null) {
                    mcpAuthService.bindOauthSubject(found.id(), oauthSub);
                }
                return session;
            }
        }

        // Tier 3: opaque mcp_session_id from LLM argument
        if (mcpSessionId != null && !mcpSessionId.isBlank()) {
            Optional<McpAuthSession> session = mcpAuthService.find(mcpSessionId);
            if (session.isPresent()) {
                McpAuthSession found = session.get();
                log.debug("mcp session resolved via opaque-id session={}", found.id().fingerprint());
                // Opportunistic: bind for faster future resolution
                if (oauthSub != null) {
                    mcpAuthService.bindOauthSubject(found.id(), oauthSub);
                }
                if (transportId != null) {
                    mcpAuthService.bindTransportId(found.id(), transportId);
                }
                return session;
            }
        }

        return Optional.empty();
    }

    /**
     * Returns the principalKey stored in the session for {@code provider}, or empty
     * if the session is missing, expired, or the provider has not been linked.
     * Uses the 3-tier resolution strategy.
     */
    public Optional<String> principalKey(String idValue, McpProviderType provider) {
        return resolveSession(idValue)
                .flatMap(session -> session.provider(provider))
                .map(McpProviderLink::principalKey);
    }

    /**
     * Resolves both the provider {@code principalKey} (studentId) and the canonical
     * resolved session id in a single 3-tier lookup. Returns empty in exactly the same
     * cases as {@link #principalKey(String, McpProviderType)} — i.e. when no session is
     * found via any tier or the provider has not been linked — so OK-path callers can
     * keep using the empty branch to emit AUTH_REQUIRED.
     *
     * <p>The {@code sessionId} is the id of the resolved {@code McpAuthSession}, which may
     * differ from {@code idValue} when the session was found via the OAuth-sub or transport
     * tier rather than the opaque argument. OK responses should echo this canonical id, not
     * the raw input argument.
     */
    public Optional<ResolvedPrincipal> resolvePrincipal(String idValue, McpProviderType provider) {
        return resolveSession(idValue)
                .flatMap(session -> session.provider(provider)
                        .map(link -> new ResolvedPrincipal(link.principalKey(), session.id().value())));
    }

    /**
     * Pairing of the provider {@code principalKey} (studentId) with the canonical resolved
     * session id, returned by {@link #resolvePrincipal(String, McpProviderType)}.
     */
    public record ResolvedPrincipal(String studentId, String sessionId) {
    }

    /**
     * Builds an AUTH_REQUIRED response using the 3-tier resolution strategy.
     * Gets-or-creates the session (so the client always gets back a stable mcpSessionId),
     * generates a one-time state token, and constructs the login URL.
     */
    public <T> McpPrivateToolResponse<T> buildAuthRequired(String idValue, McpProviderType provider) {
        McpAuthSession session;

        Optional<McpAuthSession> resolved = resolveSession(idValue);
        if (resolved.isPresent()) {
            session = resolved.get();
        } else if (idValue != null && !idValue.isBlank()) {
            // Explicit id was provided but not found via any path
            return McpPrivateToolResponse.invalidSession(idValue, provider.name());
        } else {
            // No id at all — create a fresh session
            session = mcpAuthService.createSession();
            // Eagerly bind transport id so subsequent calls can find this session
            String transportId = currentTransportId();
            if (transportId != null) {
                mcpAuthService.bindTransportId(session.id(), transportId);
            }
        }

        McpAuthStateEntry state = mcpAuthService.generateState(session.id(), provider);
        String loginUrl = urlFactory.buildLoginUrl(provider, state.state());
        return McpPrivateToolResponse.authRequired(
                session.id().value(), provider.name(), loginUrl, state.expiresAt());
    }

    /**
     * Binds the current request's transport session id to the given auth session.
     * Call this immediately after {@code start_auth} creates or reuses a session
     * so that the transport fallback path works for all subsequent tool calls.
     */
    public void bindCurrentTransportId(McpAuthSessionId sessionId) {
        String transportId = currentTransportId();
        if (transportId != null) {
            mcpAuthService.bindTransportId(sessionId, transportId);
        }
    }

    /**
     * Extracts the OAuth {@code sub} from the current SecurityContext.
     * Returns null in classic mode (no JWT in context) or when rs-enabled=false.
     */
    private String currentOauthSub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getName(); // getName() returns the sub claim on JwtAuthenticationToken
        }
        return null;
    }

    /**
     * Extracts the {@code Mcp-Session-Id} transport header from the current request.
     * Returns null when the header is absent (e.g. non-HTTP transports).
     */
    private String currentTransportId() {
        try {
            return request.getHeader("Mcp-Session-Id");
        } catch (Exception e) {
            // No active request scope (non-HTTP transport) or proxy access failure.
            // Returning null falls through to the next resolution tier; logged so
            // framework-level failures stay traceable.
            log.trace("transport session id unavailable", e);
            return null;
        }
    }
}
