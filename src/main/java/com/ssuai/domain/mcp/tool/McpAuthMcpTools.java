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
import com.ssuai.domain.auth.mcp.McpProviderCredentialService;
import com.ssuai.domain.auth.mcp.McpSessionRevocationService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.McpSessionResolver.Code;
import com.ssuai.domain.auth.mcp.McpSessionResolver.Resolution;
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
    private final McpProviderCredentialService credentialService;
    private final McpSessionRevocationService revocationService;

    public McpAuthMcpTools(
            McpAuthService mcpAuthService,
            McpAuthUrlFactory urlFactory,
            McpAuthHelper mcpAuthHelper,
            McpProviderCredentialService credentialService,
            McpSessionRevocationService revocationService
    ) {
        this.mcpAuthService = mcpAuthService;
        this.urlFactory = urlFactory;
        this.mcpAuthHelper = mcpAuthHelper;
        this.credentialService = credentialService;
        this.revocationService = revocationService;
    }

    @Tool(
            name = "get_auth_status",
            description = "SSU Campus(숭실대학교 캠퍼스)의 현재 인증 세션 상태를 반환합니다. 'status' 필드는 다음 중 하나입니다: "
                    + "OK (세션 유효 — 각 provider의 'linked' 값을 확인하세요); "
                    + "INVALID_SESSION (mcp_session_id가 전달됐지만 잘못됐거나 만료됨 — start_auth로 새로 발급받으세요. 로그인 실패로 처리하지 마세요); "
                    + "NO_SESSION (아직 mcp_session_id가 전달되지 않음). "
                    + "인증 도구 호출 전에 이 도구를 먼저 호출하면 불필요한 AUTH_REQUIRED 재시도를 줄일 수 있습니다. "
                    + "세션은 데이터베이스에 저장되어 서버 재시작 후에도 유지됩니다."
    )
    public McpAuthStatusResponse getAuthStatus(
            @ToolParam(description = "start_auth로 발급받은 MCP session ID. 없거나 유효하지 않으면 모든 provider가 미연동으로 표시됨.", required = false)
            String mcp_session_id) {
        Resolution resolution = mcpAuthHelper.resolveDetailed(mcp_session_id);
        McpAuthSession session = resolution.session();
        String sessionIdValue = resolution.resolved() ? session.id().value() : null;

        List<McpProviderStatusEntry> providers = Arrays.stream(McpProviderType.values())
                .map(p -> {
                    if (session == null) {
                        return McpProviderStatusEntry.notLinked(p);
                    }
                    return session.provider(p)
                            .map(link -> McpProviderStatusEntry.linked(
                                    p, link.linkedAt(), credentialService.health(link)))
                            .orElseGet(() -> McpProviderStatusEntry.notLinked(p));
                })
                .toList();

        // Distinguish "no id supplied" from "an id was supplied but does not resolve"
        // (expired/invalid/dropped). Otherwise a wrong mcp_session_id looks identical to
        // "valid session, nothing linked yet", so clients (ChatGPT) treat a lost session as
        // a login failure and loop. INVALID_SESSION ⇒ call start_auth for a fresh session.
        String status = resolution.code().name();
        if (resolution.resolved()) {
            log.debug("get_auth_status session={}", session.id().fingerprint());
        }
        return new McpAuthStatusResponse(status, sessionIdValue, providers);
    }

    @Tool(
            name = "start_auth",
            description = "SSU Campus(숭실대학교 캠퍼스)의 지정 provider(SAINT, LMS, LIBRARY) 브라우저 로그인 URL을 생성합니다. "
                    + "인증 도구가 AUTH_REQUIRED를 반환할 때 이 도구를 사용하세요. "
                    + "절차: 1) 이 도구를 호출해 loginUrl과 mcpSessionId를 받습니다. "
                    + "2) loginUrl 원문을 사용자에게 그대로 보여주세요. PlayMCP connector 페이지 URL이나 loginUrl과 다른 마크다운 링크로 대체하지 마세요. "
                    + "3) 사용자가 로그인 완료를 확인할 때까지 기다립니다. "
                    + "4) mcp_session_id=[mcpSessionId]를 넣어 원래 인증 도구 호출을 다시 시도합니다. "
                    + "mcp_session_id가 전달되지 않으면 새 MCP 세션을 생성합니다."
    )
    public McpAuthStartResponse startAuth(
            @ToolParam(description = "인증할 provider. SAINT: 시간표·성적·채플·졸업·장학금. LMS: 과제·퀴즈. LIBRARY: 좌석 추천·예약·내 좌석·대출.")
            String provider,
            @ToolParam(description = "재사용할 기존 MCP session ID. 없으면 새 세션이 생성됨.", required = false)
            String mcp_session_id) {
        McpProviderType providerType = parseProvider(provider);
        if (providerType == null) {
            return new McpAuthStartResponse(
                    "ERROR", provider, mcp_session_id, null, null,
                    "Unknown provider: " + provider + ". Use SAINT, LMS, or LIBRARY.");
        }

        McpAuthSession session;
        if (mcp_session_id == null || mcp_session_id.isBlank()) {
            session = mcpAuthService.createSession();
        } else {
            Resolution resolution = mcpAuthHelper.resolveForAuthentication(mcp_session_id);
            if (!resolution.resolved()) {
                return new McpAuthStartResponse(
                        resolution.code().name(), providerType.name(), null, null, null,
                        "The supplied mcp_session_id is invalid or expired. No session was rebound.");
            }
            session = resolution.session();
        }
        if (!mcpAuthHelper.bindCurrentTransportId(session.id())) {
            return new McpAuthStartResponse(
                    Code.SESSION_MISMATCH.name(), providerType.name(), null, null, null,
                    "The current authenticated client does not own the requested MCP session.");
        }

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
            description = "MCP 세션에서 특정 provider(SAINT, LMS, LIBRARY) 연결을 해제합니다. "
                    + "provider가 필요하며, mcp_session_id를 생략하면 현재 transport에 안전하게 바인딩된 세션을 사용합니다."
    )
    public McpAuthLogoutResponse logoutProvider(
            @ToolParam(description = "연동 해제할 provider: SAINT, LMS, LIBRARY 중 하나.")
            String provider,
            @ToolParam(required = false, description = "선택 MCP session ID. 생략하면 현재 MCP transport에 안전하게 바인딩된 세션을 사용합니다.")
            String mcp_session_id) {
        McpProviderType providerType = parseProvider(provider);
        if (providerType == null) {
            return new McpAuthLogoutResponse("ERROR", mcp_session_id, provider,
                    "Unknown provider: " + provider + ". Use SAINT, LMS, or LIBRARY.");
        }
        Resolution resolution = mcpAuthHelper.resolveDetailed(mcp_session_id);
        if (!resolution.resolved()) {
            return new McpAuthLogoutResponse(
                    resolution.code().name(), null, provider, "No provider was unlinked.");
        }
        McpAuthSession session = resolution.session();

        revocationService.revokeProvider(session, providerType);
        log.debug("logout_provider session={} provider={}", session.id().fingerprint(), providerType);
        return McpAuthLogoutResponse.providerLogout(session.id().value(), providerType.name());
    }

    @Tool(
            name = "logout_all",
            description = "SSU Campus(숭실대학교 캠퍼스)의 MCP 인증 세션 전체와 연결된 모든 provider를 제거합니다. "
                    + "mcp_session_id를 생략하면 현재 transport에 안전하게 바인딩된 세션을 사용합니다."
    )
    public McpAuthLogoutResponse logoutAll(
            @ToolParam(required = false, description = "완전히 무효화할 선택 MCP session ID. 생략하면 현재 transport-bound 세션을 사용합니다.")
            String mcp_session_id) {
        Resolution resolution = mcpAuthHelper.resolveDetailed(mcp_session_id);
        if (!resolution.resolved()) {
            return new McpAuthLogoutResponse(
                    resolution.code().name(), null, null, "No session was invalidated.");
        }
        McpAuthSession session = resolution.session();

        revocationService.revokeAll(session);
        log.debug("logout_all session={}", session.id().fingerprint());
        return McpAuthLogoutResponse.allLogout(session.id().value());
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
