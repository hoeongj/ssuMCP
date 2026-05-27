package com.ssuai.domain.mcp.tool;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthStateEntry;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderLink;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;

/**
 * Helper shared by all private MCP tools to resolve the principalKey from a
 * session id and to build AUTH_REQUIRED responses when the session is missing
 * or the provider has not yet been linked.
 */
@Component
public class McpAuthHelper {

    private final McpAuthService mcpAuthService;
    private final McpAuthUrlFactory urlFactory;

    public McpAuthHelper(McpAuthService mcpAuthService, McpAuthUrlFactory urlFactory) {
        this.mcpAuthService = mcpAuthService;
        this.urlFactory = urlFactory;
    }

    /**
     * Returns the principalKey stored in the session for {@code provider}, or empty
     * if the session is missing, expired, or the provider has not been linked.
     */
    public Optional<String> principalKey(String idValue, McpProviderType provider) {
        return mcpAuthService.find(idValue)
                .flatMap(session -> session.provider(provider))
                .map(McpProviderLink::principalKey);
    }

    /**
     * Builds an AUTH_REQUIRED response. Gets-or-creates the session (so the client
     * always gets back a stable mcpSessionId), generates a one-time state token, and
     * constructs the login URL.
     */
    public <T> McpPrivateToolResponse<T> buildAuthRequired(String idValue, McpProviderType provider) {
        McpAuthSession session;
        if (idValue == null || idValue.isBlank()) {
            session = mcpAuthService.createSession();
        } else {
            session = mcpAuthService.find(idValue).orElse(null);
            if (session == null) {
                return McpPrivateToolResponse.invalidSession(idValue, provider.name());
            }
        }
        McpAuthStateEntry state = mcpAuthService.generateState(session.id(), provider);
        String loginUrl = urlFactory.buildLoginUrl(provider, state.state());
        return McpPrivateToolResponse.authRequired(
                session.id().value(), provider.name(), loginUrl, state.expiresAt());
    }
}
