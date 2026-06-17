package com.ssuai.domain.mcp.tool;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthStateEntry;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpAuthLogoutResponse;
import com.ssuai.domain.auth.mcp.dto.McpAuthStartResponse;
import com.ssuai.domain.auth.mcp.dto.McpAuthStatusResponse;
import com.ssuai.domain.auth.mcp.dto.McpProviderStatusEntry;

/**
 * MCP tools for the auth session lifecycle (Task 18 Slice B).
 *
 * <p>These are the public-facing auth tools that external MCP clients call to check
 * their authentication state and initiate or revoke provider sessions. They do NOT
 * return any student ID, cookie, or token value.
 *
 * <p>All log output uses session fingerprints, never raw {@code mcpSessionId} values.
 */
@Component
public class McpAuthMcpTools {

    private static final Logger log = LoggerFactory.getLogger(McpAuthMcpTools.class);

    private final McpAuthService mcpAuthService;
    private final McpAuthUrlFactory urlFactory;
    private final McpAuthHelper mcpAuthHelper;

    public McpAuthMcpTools(
            McpAuthService mcpAuthService,
            McpAuthUrlFactory urlFactory,
            McpAuthHelper mcpAuthHelper
    ) {
        this.mcpAuthService = mcpAuthService;
        this.urlFactory = urlFactory;
        this.mcpAuthHelper = mcpAuthHelper;
    }

    @Tool(
            name = "get_auth_status",
            description = "Returns the current MCP auth session status for each provider (SAINT, LMS, LIBRARY). "
                    + "Call this before private tools when you have an mcp_session_id and want to avoid unnecessary AUTH_REQUIRED retries. "
                    + "If mcp_session_id is missing or invalid, all providers show as not linked. "
                    + "Sessions are persisted in the database and survive server restarts — call start_auth again only if your session has expired."
    )
    public McpAuthStatusResponse getAuthStatus(
            @ToolParam(description = "MCP session ID issued by start_auth. If absent or invalid, all providers show as not linked.", required = false)
            String mcp_session_id) {
        McpAuthSession session = mcpAuthHelper.resolveSession(mcp_session_id).orElse(null);
        String sessionIdValue = session != null ? session.id().value() : null;

        List<McpProviderStatusEntry> providers = Arrays.stream(McpProviderType.values())
                .map(p -> {
                    if (session == null) {
                        return McpProviderStatusEntry.notLinked(p);
                    }
                    return session.provider(p)
                            .map(link -> McpProviderStatusEntry.linked(p, link.linkedAt()))
                            .orElseGet(() -> McpProviderStatusEntry.notLinked(p));
                })
                .toList();

        if (session != null) {
            log.debug("get_auth_status session={}", session.id().fingerprint());
        }
        return new McpAuthStatusResponse(sessionIdValue, providers);
    }

    @Tool(
            name = "start_auth",
            description = "Generates a browser login URL for the specified provider (SAINT, LMS, or LIBRARY). "
                    + "Use this tool when a private tool returns AUTH_REQUIRED. "
                    + "Steps: 1) Call this tool to get loginUrl and mcpSessionId. "
                    + "2) Paste the raw loginUrl as visible text for the user; do not replace it with a PlayMCP connector page URL or a markdown link target that differs from loginUrl. "
                    + "3) Wait for the user to confirm login is complete. "
                    + "4) Retry the original private tool call with mcp_session_id=[mcpSessionId]. "
                    + "Creates a new MCP session if mcp_session_id is not provided."
    )
    public McpAuthStartResponse startAuth(
            @ToolParam(description = "Provider to authenticate. SAINT: 시간표·성적·채플·졸업·장학금. LMS: 과제·퀴즈. LIBRARY: 도서관 좌석·대출 현황.")
            String provider,
            @ToolParam(description = "Existing MCP session ID to reuse. If absent, a new session is created.", required = false)
            String mcp_session_id) {
        McpProviderType providerType = parseProvider(provider);
        if (providerType == null) {
            return new McpAuthStartResponse(
                    "ERROR", provider, mcp_session_id, null, null,
                    "Unknown provider: " + provider + ". Use SAINT, LMS, or LIBRARY.");
        }

        McpAuthSession session = mcpAuthService.getOrCreate(mcp_session_id);
        // Bind transport id immediately so subsequent private tool calls can find this
        // session via the transport fallback path (ADR 0036 §1B), even when the LLM
        // drops the opaque mcp_session_id across turns (e.g. ChatGPT turn-boundary drop).
        mcpAuthHelper.bindCurrentTransportId(session.id());

        McpAuthStateEntry state = mcpAuthService.generateState(session.id(), providerType);
        String loginUrl = urlFactory.buildLoginUrl(providerType, state.state());

        log.debug("start_auth session={} provider={}", session.id().fingerprint(), providerType);
        return new McpAuthStartResponse(
                "LOGIN_STARTED",
                providerType.name(),
                session.id().value(),
                loginUrl,
                state.expiresAt(),
                "Open this exact loginUrl in a browser: " + loginUrl
                        + " Do not substitute a PlayMCP or connector page URL. "
                        + "After login is complete, call private tools with mcp_session_id.");
    }

    @Tool(
            name = "logout_provider",
            description = "Unlinks a specific provider (SAINT, LMS, or LIBRARY) from the MCP session. "
                    + "Requires both mcp_session_id and provider."
    )
    public McpAuthLogoutResponse logoutProvider(
            @ToolParam(description = "Provider to unlink: SAINT, LMS, or LIBRARY.")
            String provider,
            @ToolParam(description = "MCP session ID issued by start_auth.")
            String mcp_session_id) {
        McpProviderType providerType = parseProvider(provider);
        if (providerType == null) {
            return new McpAuthLogoutResponse("ERROR", mcp_session_id, provider,
                    "Unknown provider: " + provider + ". Use SAINT, LMS, or LIBRARY.");
        }
        if (mcp_session_id == null || mcp_session_id.isBlank()) {
            return new McpAuthLogoutResponse("ERROR", null, provider, "mcp_session_id is required.");
        }

        McpAuthSession session = mcpAuthService.find(mcp_session_id).orElse(null);
        if (session == null) {
            return new McpAuthLogoutResponse("ERROR", mcp_session_id, provider,
                    "No valid MCP session found.");
        }

        mcpAuthService.unlinkProvider(session.id(), providerType);
        log.debug("logout_provider session={} provider={}", session.id().fingerprint(), providerType);
        return McpAuthLogoutResponse.providerLogout(session.id().value(), providerType.name());
    }

    @Tool(
            name = "logout_all",
            description = "Removes the entire MCP auth session and all linked providers. "
                    + "Requires mcp_session_id."
    )
    public McpAuthLogoutResponse logoutAll(
            @ToolParam(description = "MCP session ID to fully invalidate.")
            String mcp_session_id) {
        if (mcp_session_id == null || mcp_session_id.isBlank()) {
            return new McpAuthLogoutResponse("ERROR", null, null, "mcp_session_id is required.");
        }

        McpAuthSession session = mcpAuthService.find(mcp_session_id).orElse(null);
        if (session == null) {
            return new McpAuthLogoutResponse("ERROR", mcp_session_id, null,
                    "No valid MCP session found.");
        }

        mcpAuthService.invalidateSession(session.id());
        log.debug("logout_all session={}", new McpAuthSessionId(mcp_session_id).fingerprint());
        return McpAuthLogoutResponse.allLogout(mcp_session_id);
    }

    private static McpProviderType parseProvider(String provider) {
        if (provider == null) {
            return null;
        }
        try {
            return McpProviderType.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
