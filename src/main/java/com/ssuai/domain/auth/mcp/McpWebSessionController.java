package com.ssuai.domain.auth.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.auth.mcp.dto.McpWebSessionResponse;
import com.ssuai.domain.library.auth.LibrarySessionKeyResolver;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.global.auth.AuthUser;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

/**
 * Issues an mcp_session_id for ssuAI web users authenticated by either the
 * SAINT JWT or an active library cookie session.
 *
 * <p>POST /api/mcp/auth/web-session
 *   - Links SAINT + LMS when a JWT student ID is present.
 *   - Links LIBRARY if an active library session exists in the HTTP session.
 *   - Returns {mcpSessionId, expiresAt}.
 *
 * <p>The library is an independent auth provider backed by its own cookie
 *   session. ssuAI chat must keep working for library-only users, including
 *   when u-SAINT is unavailable for maintenance and no SAINT JWT can be issued.
 *
 * <p>Rationale: External MCP clients go through the full
 *   start_auth browser redirect flow. ssuAI web users may already be
 *   authenticated through one or more web providers and do not need a separate
 *   browser step.
 */
@RestController
@RequestMapping("/api/mcp/auth/web-session")
public class McpWebSessionController {

    private static final Logger log = LoggerFactory.getLogger(McpWebSessionController.class);

    private final McpAuthService mcpAuthService;
    private final LibrarySessionStore librarySessionStore;
    private final LibrarySessionKeyResolver librarySessionKeyResolver;

    public McpWebSessionController(
            McpAuthService mcpAuthService,
            LibrarySessionStore librarySessionStore,
            LibrarySessionKeyResolver librarySessionKeyResolver) {
        this.mcpAuthService = mcpAuthService;
        this.librarySessionStore = librarySessionStore;
        this.librarySessionKeyResolver = librarySessionKeyResolver;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpWebSessionResponse> create(
            @AuthUser(required = false) String studentId,
            HttpServletRequest request) {

        String librarySessionKey = activeLibrarySessionKey(request);
        if (studentId == null && librarySessionKey == null) {
            throw new UnauthorizedException();
        }

        McpAuthSession session = mcpAuthService.createSession();
        McpAuthSessionId sessionId = session.id();

        // SAINT + LMS: link only when JWT identity is available.
        if (studentId != null) {
            mcpAuthService.linkProvider(sessionId, McpProviderType.SAINT, studentId);
            mcpAuthService.linkProvider(sessionId, McpProviderType.LMS, studentId);
        }

        // LIBRARY: link only if an active HTTP session with a stored library token exists
        if (librarySessionKey != null) {
            mcpAuthService.linkProvider(sessionId, McpProviderType.LIBRARY, librarySessionKey);
            log.debug("web-session: library linked");
        }

        log.debug("web-session: created session={}", sessionId.fingerprint());
        return ApiResponse.success(new McpWebSessionResponse(sessionId.value(), session.expiresAt()));
    }

    private String activeLibrarySessionKey(HttpServletRequest request) {
        // Prefers the persistent library-session cookie (survives redeploys/pod switches),
        // falling back to a legacy servlet session id (ADR 0096).
        return librarySessionKeyResolver.resolve(request)
                .filter(librarySessionStore::has)
                .orElse(null);
    }
}
