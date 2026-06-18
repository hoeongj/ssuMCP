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
import com.ssuai.global.exception.LmsAuthFailedException;

/**
 * MCP auth flow for LMS (Task 18 Slice B). Same shape as {@link McpSaintAuthController}.
 *
 * <p>GET /api/mcp/auth/lms/start?state=...   → redirect to SmartID
 * <p>GET /api/mcp/auth/lms/callback?sToken=...&sIdno=...&state=... → link LMS provider
 *
 * <p>sToken/sIdno/state raw values are never logged.
 */
@RestController
@RequestMapping("/api/mcp/auth/lms")
public class McpLmsAuthController {

    private static final Logger log = LoggerFactory.getLogger(McpLmsAuthController.class);

    private final LmsSsoService lmsSsoService;
    private final McpAuthService mcpAuthService;
    private final McpAuthUrlFactory urlFactory;
    private final AuthProperties authProperties;

    public McpLmsAuthController(
            LmsSsoService lmsSsoService,
            McpAuthService mcpAuthService,
            McpAuthUrlFactory urlFactory,
            AuthProperties authProperties) {
        this.lmsSsoService = lmsSsoService;
        this.mcpAuthService = mcpAuthService;
        this.urlFactory = urlFactory;
        this.authProperties = authProperties;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam String state) {
        String callbackUrl = urlFactory.buildCallbackUrl(McpProviderType.LMS, state);
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

        // Same SmartID double-? quirk as McpSaintAuthController.
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
            log.warn("mcp lms callback: state invalid or expired");
            return completionPage(false, "인증 요청이 만료되었거나 유효하지 않습니다. start_auth를 다시 호출해주세요.");
        }
        if (entry.provider() != McpProviderType.LMS) {
            log.warn("mcp lms callback: provider mismatch expected=LMS actual={}", entry.provider());
            return completionPage(false, "잘못된 인증 요청입니다. provider가 일치하지 않습니다.");
        }
        if (sIdno == null || sIdno.isBlank()) {
            log.warn("mcp lms callback: sIdno missing session={}", entry.mcpSessionId().fingerprint());
            return completionPage(false, "학번 정보를 받지 못했습니다. 다시 시도해주세요.");
        }

        try {
            lmsSsoService.authenticate(sToken, sIdno);
        } catch (LmsAuthFailedException e) {
            log.info("mcp lms callback: auth failed session={}", entry.mcpSessionId().fingerprint());
            return completionPage(false, "LMS 로그인에 실패했습니다. 다시 시도해주세요.");
        } catch (Exception e) {
            log.warn("mcp lms callback: unexpected error session={}", entry.mcpSessionId().fingerprint());
            return completionPage(false, "로그인 중 오류가 발생했습니다. 다시 시도해주세요.");
        }

        // sIdno is the student number (same as studentId), used as LmsSessionStore key.
        mcpAuthService.linkProvider(entry.mcpSessionId(), McpProviderType.LMS, sIdno.trim());
        log.debug("mcp lms callback: linked session={}", entry.mcpSessionId().fingerprint());
        return completionPage(true, "LMS 로그인 완료. MCP 클라이언트로 돌아가 다시 요청하세요.");
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
                + (success ? "<p style=\"color:#059669;font-weight:600;margin-top:20px\">"
                        + "🔒 입력하신 로그인 정보는 서버에 저장되지 않습니다.</p>" : "")
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
