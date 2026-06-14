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
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.global.auth.AuthUser;
import com.ssuai.global.response.ApiResponse;

/**
 * Issues an mcp_session_id for ssuAI web users already authenticated via JWT.
 *
 * <p>POST /api/mcp/auth/web-session (JWT Bearer required)
 *   - Links SAINT + LMS from JWT student ID.
 *   - Links LIBRARY if an active library session exists in the HTTP session.
 *   - Returns {mcpSessionId, expiresAt}.
 *
 * <p>Rationale: External MCP clients (Claude Desktop) go through the full
 *   start_auth browser redirect flow. ssuAI web users are already authenticated
 *   and do not need a separate browser step.
 */
@RestController
@RequestMapping("/api/mcp/auth/web-session")
public class McpWebSessionController {

    private static final Logger log = LoggerFactory.getLogger(McpWebSessionController.class);

    private final McpAuthService mcpAuthService;
    private final LibrarySessionStore librarySessionStore;

    public McpWebSessionController(
            McpAuthService mcpAuthService,
            LibrarySessionStore librarySessionStore) {
        this.mcpAuthService = mcpAuthService;
        this.librarySessionStore = librarySessionStore;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpWebSessionResponse> create(
            @AuthUser String studentId,
            HttpServletRequest request) {

        McpAuthSession session = mcpAuthService.createSession();
        McpAuthSessionId sessionId = session.id();

        // SAINT + LMS: always linked from JWT identity
        mcpAuthService.linkProvider(sessionId, McpProviderType.SAINT, studentId);
        mcpAuthService.linkProvider(sessionId, McpProviderType.LMS, studentId);

        // LIBRARY: link only if an active HTTP session with a stored library token exists
        jakarta.servlet.http.HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            String sessionKey = httpSession.getId();
            if (librarySessionStore.has(sessionKey)) {
                mcpAuthService.linkProvider(sessionId, McpProviderType.LIBRARY, sessionKey);
                log.debug("web-session: library linked for student={}", studentId.substring(0, Math.min(4, studentId.length())));
            }
        }

        log.debug("web-session: created session={}", sessionId.fingerprint());
        return ApiResponse.success(new McpWebSessionResponse(sessionId.value(), session.expiresAt()));
    }
}
