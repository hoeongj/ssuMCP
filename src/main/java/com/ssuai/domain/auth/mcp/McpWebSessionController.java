package com.ssuai.domain.auth.mcp;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.auth.mcp.dto.McpWebSessionResponse;
import com.ssuai.domain.auth.mcp.dto.McpWebSessionStatusRequest;
import com.ssuai.domain.library.auth.LibrarySessionKeyResolver;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.global.auth.AuthUser;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

/**
 * Issues an mcp_session_id for ssuAI web users authenticated by either the
 * SAINT JWT or an active library cookie session.
 *
 * <p>POST /api/mcp/auth/web-session
 *   - Copies and links each currently valid SAINT/LMS credential when a JWT identity is present.
 *   - Copies and links LIBRARY if an active library session exists in the HTTP session.
 *   - Returns {mcpSessionId, expiresAt, linkedProviders, availableProviders, providerHealth}.
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
    private final SaintSessionStore saintSessionStore;
    private final LmsSessionStore lmsSessionStore;
    private final McpProviderCredentialService credentialService;

    public McpWebSessionController(
            McpAuthService mcpAuthService,
            LibrarySessionStore librarySessionStore,
            LibrarySessionKeyResolver librarySessionKeyResolver,
            SaintSessionStore saintSessionStore,
            LmsSessionStore lmsSessionStore,
            McpProviderCredentialService credentialService) {
        this.mcpAuthService = mcpAuthService;
        this.librarySessionStore = librarySessionStore;
        this.librarySessionKeyResolver = librarySessionKeyResolver;
        this.saintSessionStore = saintSessionStore;
        this.lmsSessionStore = lmsSessionStore;
        this.credentialService = credentialService;
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
        EnumSet<McpProviderType> linkedProviders = EnumSet.noneOf(McpProviderType.class);
        EnumSet<McpProviderType> availableProviders = EnumSet.noneOf(McpProviderType.class);
        EnumMap<McpProviderType, McpProviderHealth> providerHealth =
                new EnumMap<>(McpProviderType.class);
        String saintOwnerKey = null;
        String lmsOwnerKey = null;
        String libraryOwnerKey = null;
        try {
            if (studentId != null
                    && !mcpAuthService.bindOrVerifyOauthSubject(sessionId, studentId)) {
                throw new UnauthorizedException();
            }

            // A JWT identifies the web user, but the credential copy is isolated under
            // this newly issued MCP session. No MCP session links directly to studentId.
            if (studentId != null) {
                saintOwnerKey = McpCredentialNamespace.generate();
                if (saintSessionStore.copyForSession(studentId, saintOwnerKey)) {
                    mcpAuthService.linkProvider(sessionId, McpProviderType.SAINT, saintOwnerKey);
                    recordProviderStatus(
                            session, McpProviderType.SAINT, saintOwnerKey,
                            linkedProviders, availableProviders, providerHealth);
                }
                lmsOwnerKey = McpCredentialNamespace.generate();
                if (lmsSessionStore.copyForSession(studentId, lmsOwnerKey)) {
                    mcpAuthService.linkProvider(sessionId, McpProviderType.LMS, lmsOwnerKey);
                    recordProviderStatus(
                            session, McpProviderType.LMS, lmsOwnerKey,
                            linkedProviders, availableProviders, providerHealth);
                }
            }

            // LIBRARY: link only if an active HTTP session with a stored library token exists
            if (librarySessionKey != null) {
                libraryOwnerKey = McpCredentialNamespace.generate();
                if (librarySessionStore.copy(librarySessionKey, libraryOwnerKey)) {
                    mcpAuthService.linkProvider(
                            sessionId, McpProviderType.LIBRARY, libraryOwnerKey);
                    recordProviderStatus(
                            session, McpProviderType.LIBRARY, libraryOwnerKey,
                            linkedProviders, availableProviders, providerHealth);
                    log.debug("web-session: library linked");
                }
            }
        } catch (RuntimeException failure) {
            cleanupFailedIssuance(
                    sessionId, saintOwnerKey, lmsOwnerKey, libraryOwnerKey, failure);
            throw failure;
        }

        log.debug(
                "web-session: created session={} linkedProviders={} availableProviders={} providerHealth={}",
                sessionId.fingerprint(),
                linkedProviders,
                availableProviders,
                providerHealth);
        return ApiResponse.success(new McpWebSessionResponse(
                sessionId.value(), session.expiresAt(), linkedProviders,
                availableProviders, providerHealth));
    }

    /**
     * Re-reads an existing browser-owned MCP session so provider callbacks, logout,
     * and credential expiry are reflected without rotating the session identifier.
     */
    @PostMapping("/status")
    public ApiResponse<McpWebSessionResponse> status(
            @AuthUser(required = false) String studentId,
            @Valid @RequestBody McpWebSessionStatusRequest statusRequest,
            HttpServletRequest request) {
        String librarySessionKey = activeLibrarySessionKey(request);
        if (studentId == null && librarySessionKey == null) {
            throw new UnauthorizedException();
        }

        McpAuthSession session = mcpAuthService.find(statusRequest.mcpSessionId())
                .orElseThrow(UnauthorizedException::new);
        if (studentId != null
                && !mcpAuthService.verifyOauthSubject(session.id(), studentId)) {
            throw new UnauthorizedException();
        }

        EnumSet<McpProviderType> linkedProviders = EnumSet.noneOf(McpProviderType.class);
        EnumSet<McpProviderType> availableProviders = EnumSet.noneOf(McpProviderType.class);
        EnumMap<McpProviderType, McpProviderHealth> providerHealth =
                new EnumMap<>(McpProviderType.class);
        session.providers().forEach((provider, link) -> {
            McpProviderCredentialStatus status = credentialService.status(link);
            providerHealth.put(provider, status.health().health());
            if (status.linked()) {
                linkedProviders.add(provider);
            }
            if (status.available()) {
                availableProviders.add(provider);
            }
        });
        log.debug(
                "web-session: refreshed session={} linkedProviders={} availableProviders={} providerHealth={}",
                session.id().fingerprint(),
                linkedProviders,
                availableProviders,
                providerHealth);
        return ApiResponse.success(new McpWebSessionResponse(
                session.id().value(), session.expiresAt(), linkedProviders,
                availableProviders, providerHealth));
    }

    private void recordProviderStatus(
            McpAuthSession session,
            McpProviderType provider,
            String ownerKey,
            EnumSet<McpProviderType> linkedProviders,
            EnumSet<McpProviderType> availableProviders,
            Map<McpProviderType, McpProviderHealth> providerHealth) {
        McpProviderLink link = new McpProviderLink(provider, ownerKey, session.createdAt());
        McpProviderCredentialStatus status = credentialService.status(link);
        providerHealth.put(provider, status.health().health());
        if (status.linked()) {
            linkedProviders.add(provider);
        }
        if (status.available()) {
            availableProviders.add(provider);
        }
    }

    private String activeLibrarySessionKey(HttpServletRequest request) {
        // Prefers the persistent library-session cookie (survives redeploys/pod switches),
        // falling back to a legacy servlet session id (ADR 0096).
        return librarySessionKeyResolver.resolve(request)
                .filter(librarySessionStore::has)
                .orElse(null);
    }

    private void cleanupFailedIssuance(
            McpAuthSessionId sessionId,
            String saintOwnerKey,
            String lmsOwnerKey,
            String libraryOwnerKey,
            RuntimeException failure) {
        cleanup(() -> mcpAuthService.invalidateSession(sessionId), failure);
        if (saintOwnerKey != null) {
            cleanup(() -> saintSessionStore.invalidate(saintOwnerKey), failure);
        }
        if (lmsOwnerKey != null) {
            cleanup(() -> lmsSessionStore.invalidate(lmsOwnerKey), failure);
        }
        if (libraryOwnerKey != null) {
            cleanup(() -> librarySessionStore.invalidate(libraryOwnerKey), failure);
        }
    }

    private void cleanup(Runnable action, RuntimeException failure) {
        try {
            action.run();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
