package com.ssuai.domain.auth.saint;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.auth.lms.LmsSsoService;
import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.service.StudentService;
import com.ssuai.global.auth.JwtProperties;
import com.ssuai.global.auth.JwtProvider;
import com.ssuai.global.exception.SaintAuthFailedException;
import com.ssuai.global.exception.SaintPortalUnavailableException;

/**
 * u-SAINT SSO entry + callback (Task 14). Implements the redirect-callback
 * pattern from ADR 0014: the browser is sent to SmartID with our backend's
 * own URL as {@code apiReturnUrl}, so SmartID 302s back here with
 * {@code sToken} + {@code sIdno} in the query string. Same origin, no SOP
 * dance.
 *
 * <p>This controller never sees the user's SSU password — SmartID handles
 * that on its own login page. The one-shot tokens received here are
 * consumed inside {@link SaintSsoService#authenticate(String, String)}
 * and discarded; only the resulting ssuAI JWT pair leaves the method.
 *
 * <p>All error paths 302-redirect to the frontend with an
 * {@code ?error=...} query parameter rather than returning a JSON error
 * envelope, because the browser is mid-navigation and the user-visible
 * surface is the frontend `/auth/return` page.
 */
@RestController
@RequestMapping("/api/auth/saint")
public class SaintSsoCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SaintSsoCallbackController.class);

    private final SaintSsoService saintSsoService;
    private final LmsSsoService lmsSsoService;
    private final StudentService studentService;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final AuthProperties authProperties;
    private final String frontendOrigin;

    public SaintSsoCallbackController(
            SaintSsoService saintSsoService,
            LmsSsoService lmsSsoService,
            StudentService studentService,
            JwtProvider jwtProvider,
            JwtProperties jwtProperties,
            AuthProperties authProperties,
            @Value("${ssuai.frontend.origin:}") String frontendOrigin) {
        if (frontendOrigin == null || frontendOrigin.isBlank()) {
            throw new IllegalStateException(
                    "ssuai.frontend.origin (env: SSUAI_FRONTEND_ORIGIN) must be set; "
                            + "the SSO callback cannot 302 the user back to the frontend without it.");
        }
        String apiBaseUrl = authProperties.getApiBaseUrl();
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "ssuai.auth.api-base-url (env: SSUAI_API_BASE_URL) must be set; "
                            + "SmartID needs an absolute apiReturnUrl. A blank value yields "
                            + "the relative path /api/auth/saint/sso-callback, which SmartID "
                            + "resolves against its own host and the callback never reaches us.");
        }
        this.saintSsoService = saintSsoService;
        this.lmsSsoService = lmsSsoService;
        this.studentService = studentService;
        this.jwtProvider = jwtProvider;
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping("/sso-init")
    public ResponseEntity<Void> ssoInit() {
        String callback = authProperties.getApiBaseUrl() + "/api/auth/saint/sso-callback";
        String encoded = URLEncoder.encode(callback, StandardCharsets.UTF_8);
        URI redirect = URI.create(
                authProperties.getSmartidSsoUrl() + "?apiReturnUrl=" + encoded);
        return ResponseEntity.status(HttpStatus.FOUND).location(redirect).build();
    }

    @GetMapping("/sso-callback")
    public ResponseEntity<String> ssoCallback(
            @RequestParam(required = false) String sToken,
            @RequestParam(required = false) String sIdno,
            HttpServletResponse response) {
        try {
            UsaintAuthResult identity = saintSsoService.authenticate(sToken, sIdno);
            Student student = studentService.upsertOnLogin(
                    identity.studentId(),
                    identity.name(),
                    identity.major(),
                    identity.enrollmentStatus());

            String refresh = jwtProvider.issueRefresh(student);
            response.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(refresh).toString());

            // Best-effort LMS auth with the same one-shot SmartID tokens.
            // LMS uses an identical sToken/sIdno flow (lms.ssu.ac.kr/xn-sso/gw-cb.php).
            // A failure here must never block the ssuAI JWT — the user lands on the
            // dashboard authenticated; only LMS-specific cards degrade.
            try {
                lmsSsoService.authenticate(sToken, sIdno);
            } catch (Exception lmsEx) {
                log.info("saint sso-callback: LMS auth skipped ({})", lmsEx.getMessage());
            }

            // Return 200 + meta-refresh HTML instead of 302 so that Vercel's
            // rewrite proxy forwards the Set-Cookie header to the browser.
            // Vercel drops Set-Cookie on proxied 302 responses, so the refresh
            // cookie would never reach the browser if we used a plain redirect.
            return htmlRedirect(frontendReturn("ok", "1"));
        } catch (SaintAuthFailedException exception) {
            log.info("saint sso-callback auth failed: {}", exception.getMessage());
            return redirect(frontendReturn("error", "auth_failed"));
        } catch (SaintPortalUnavailableException exception) {
            log.warn("saint sso-callback portal unavailable: {}", exception.getMessage());
            return redirect(frontendReturn("error", "portal_unavailable"));
        } catch (Exception exception) {
            // Catch-all so the user is always returned to the frontend with
            // an actionable error, never left on a backend 5xx page.
            log.warn("saint sso-callback unknown failure", exception);
            return redirect(frontendReturn("error", "unknown"));
        }
    }

    private ResponseCookie buildRefreshCookie(String refreshToken) {
        AuthProperties.RefreshCookie cookieConfig = authProperties.getRefreshCookie();
        return ResponseCookie.from(cookieConfig.getName(), refreshToken)
                .httpOnly(true)
                .secure(cookieConfig.isSecure())
                .sameSite(cookieConfig.getSameSite())
                .path(cookieConfig.getPath())
                .maxAge(jwtProperties.getRefreshTtl())
                .build();
    }

    private URI frontendReturn(String key, String value) {
        return URI.create(frontendOrigin + "/auth/return?" + key + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static ResponseEntity<String> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    private static ResponseEntity<String> htmlRedirect(URI location) {
        String url = location.toString()
                .replace("&", "&amp;")
                .replace("\"", "&quot;");
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"refresh\" content=\"0;url=" + url + "\">"
                + "</head><body></body></html>";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .body(html);
    }
}
