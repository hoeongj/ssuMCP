package com.ssuai.domain.auth.mcp;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.auth.lms.LmsSsoService;
import com.ssuai.domain.auth.saint.SaintSsoService;
import com.ssuai.domain.auth.saint.UsaintAuthResult;
import com.ssuai.global.exception.LmsAuthFailedException;
import com.ssuai.global.exception.SaintAuthFailedException;
import com.ssuai.global.exception.SaintPortalUnavailableException;

/**
 * MCP auth flow for u-SAINT (Task 18 Slice B).
 *
 * <p>GET /api/mcp/auth/saint/start?state=...
 *   Redirects the browser to SmartID with our callback URL embedded.
 *   The state is forwarded so the callback can correlate the MCP session.
 *
 * <p>GET /api/mcp/auth/saint/callback?sToken=...&sIdno=...&state=...
 *   SmartID redirects here after the user logs in. Consumes the one-time state,
 *   authenticates with u-SAINT, links the provider session, and returns a plain
 *   HTML completion page (no JWT issued — this is an MCP-only flow).
 *
 * <p>Security:
 * <ul>
 *   <li>sToken / sIdno are never logged.
 *   <li>state token raw value is never logged.
 *   <li>studentId (principalKey) is stored in the MCP session but not echoed in HTML.
 *   <li>Callback HTML never contains upstream cookies, tokens, or session ids.
 * </ul>
 */
@RestController
@RequestMapping("/api/mcp/auth/saint")
public class McpSaintAuthController {

    private static final Logger log = LoggerFactory.getLogger(McpSaintAuthController.class);

    private final SaintSsoService saintSsoService;
    private final LmsSsoService lmsSsoService;
    private final McpAuthService mcpAuthService;
    private final McpAuthUrlFactory urlFactory;
    private final AuthProperties authProperties;

    public McpSaintAuthController(
            SaintSsoService saintSsoService,
            LmsSsoService lmsSsoService,
            McpAuthService mcpAuthService,
            McpAuthUrlFactory urlFactory,
            AuthProperties authProperties) {
        this.saintSsoService = saintSsoService;
        this.lmsSsoService = lmsSsoService;
        this.mcpAuthService = mcpAuthService;
        this.urlFactory = urlFactory;
        this.authProperties = authProperties;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam String state) {
        String callbackUrl = urlFactory.buildCallbackUrl(McpProviderType.SAINT, state);
        String encoded = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
        URI smartIdUrl = URI.create(
                authProperties.getSmartidSsoUrl() + "?apiReturnUrl=" + encoded);
        return ResponseEntity.status(HttpStatus.FOUND).location(smartIdUrl).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String sToken,
            @RequestParam(required = false) String sIdno,
            @RequestParam(required = false) String state) {

        // SmartID appends ?sToken=...&sIdno=... with ? instead of & when our URL already
        // contains ?state=..., so state param becomes "<uuid>?sToken=<token>" and sToken is null.
        if (state != null && state.contains("?")) {
            int q = state.indexOf('?');
            String suffix = state.substring(q + 1);
            state = state.substring(0, q);
            for (String pair : suffix.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2) {
                    if ("sToken".equals(parts[0]) && sToken == null) {
                        sToken = parts[1];
                    }
                    if ("sIdno".equals(parts[0]) && sIdno == null) {
                        sIdno = parts[1];
                    }
                }
            }
        }

        McpAuthStateEntry entry = mcpAuthService.consumeState(state).orElse(null);
        if (entry == null) {
            log.warn("mcp saint callback: state invalid or expired");
            return completionPage(false, "인증 요청이 만료되었거나 유효하지 않습니다. start_auth를 다시 호출해주세요.");
        }
        if (entry.provider() != McpProviderType.SAINT) {
            log.warn("mcp saint callback: provider mismatch expected=SAINT actual={}", entry.provider());
            return completionPage(false, "잘못된 인증 요청입니다. provider가 일치하지 않습니다.");
        }

        UsaintAuthResult identity;
        try {
            identity = saintSsoService.authenticate(sToken, sIdno);
        } catch (SaintAuthFailedException e) {
            log.info("mcp saint callback: auth failed session={}", entry.mcpSessionId().fingerprint());
            return completionPage(false, "u-SAINT 로그인에 실패했습니다. 다시 시도해주세요.");
        } catch (SaintPortalUnavailableException e) {
            log.warn("mcp saint callback: portal unavailable session={}", entry.mcpSessionId().fingerprint());
            return completionPage(false, "u-SAINT 서버에 접속할 수 없습니다. 잠시 후 다시 시도해주세요.");
        } catch (Exception e) {
            log.warn("mcp saint callback: unexpected error session={}", entry.mcpSessionId().fingerprint());
            return completionPage(false, "로그인 중 오류가 발생했습니다. 다시 시도해주세요.");
        }

        mcpAuthService.linkProvider(entry.mcpSessionId(), McpProviderType.SAINT, identity.studentId());
        log.debug("mcp saint callback: linked session={}", entry.mcpSessionId().fingerprint());

        // Best-effort LMS link with the same SmartID one-shot tokens.
        // LMS failure must not block SAINT success.
        try {
            lmsSsoService.authenticate(sToken, sIdno);
            mcpAuthService.linkProvider(entry.mcpSessionId(), McpProviderType.LMS, identity.studentId());
            log.debug("mcp saint callback: LMS also linked session={}", entry.mcpSessionId().fingerprint());
        } catch (LmsAuthFailedException e) {
            log.info("mcp saint callback: LMS best-effort skipped session={}", entry.mcpSessionId().fingerprint());
        } catch (Exception e) {
            log.info("mcp saint callback: LMS best-effort error session={}", entry.mcpSessionId().fingerprint());
        }

        return completionPage(true, "u-SAINT 로그인 완료. MCP 클라이언트로 돌아가 다시 요청하세요.");
    }

    private static ResponseEntity<String> completionPage(boolean success, String message) {
        String title = success ? "로그인 완료" : "로그인 실패";
        String html = "<!DOCTYPE html><html lang=\"ko\"><head>"
                + "<meta charset=\"UTF-8\">"
                + "<title>" + title + "</title>"
                + "<style>body{font-family:sans-serif;text-align:center;padding:40px}"
                + ".ok{color:#2563eb}.err{color:#dc2626}</style>"
                + "</head><body>"
                + "<h2 class=\"" + (success ? "ok" : "err") + "\">" + title + "</h2>"
                + "<p>" + escapeHtml(message) + "</p>"
                + "</body></html>";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + "; charset=UTF-8")
                .body(html);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
