package com.ssuai.domain.auth.lms;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.global.exception.LmsAuthFailedException;

/**
 * LMS SSO entry + callback. Same redirect-callback pattern as
 * {@code SaintSsoCallbackController}: browser → SmartID → us → frontend.
 *
 * <p>{@code GET /api/auth/lms/sso-init} redirects the browser to SmartID
 * with our LMS callback as {@code apiReturnUrl}.
 *
 * <p>{@code GET /api/auth/lms/sso-callback} receives the one-shot
 * {@code sToken} / {@code sIdno} tokens from SmartID, authenticates with
 * the LMS via {@link LmsSsoService}, stores the canvas session cookies,
 * and redirects the browser to the frontend.
 */
@RestController
@RequestMapping("/api/auth/lms")
public class LmsSsoCallbackController {

    private static final Logger log = LoggerFactory.getLogger(LmsSsoCallbackController.class);

    private final LmsSsoService lmsSsoService;
    private final AuthProperties authProperties;
    private final String frontendOrigin;

    public LmsSsoCallbackController(
            LmsSsoService lmsSsoService,
            AuthProperties authProperties,
            @Value("${ssuai.frontend.origin:}") String frontendOrigin) {
        if (frontendOrigin == null || frontendOrigin.isBlank()) {
            throw new IllegalStateException(
                    "ssuai.frontend.origin must be set for LMS SSO callback redirect.");
        }
        this.lmsSsoService = lmsSsoService;
        this.authProperties = authProperties;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping("/sso-init")
    public ResponseEntity<Void> ssoInit() {
        String callback = authProperties.getApiBaseUrl() + "/api/auth/lms/sso-callback";
        String encoded = URLEncoder.encode(callback, StandardCharsets.UTF_8);
        URI redirect = URI.create(authProperties.getSmartidSsoUrl() + "?apiReturnUrl=" + encoded);
        return ResponseEntity.status(HttpStatus.FOUND).location(redirect).build();
    }

    @GetMapping("/sso-callback")
    public ResponseEntity<Void> ssoCallback(
            @RequestParam(required = false) String sToken,
            @RequestParam(required = false) String sIdno) {
        try {
            lmsSsoService.authenticate(sToken, sIdno);
            return redirect(frontendReturn("lms_ok", "1"));
        } catch (LmsAuthFailedException exception) {
            log.info("lms sso-callback auth failed: {}", exception.getMessage());
            return redirect(frontendReturn("error", "lms_auth_failed"));
        } catch (Exception exception) {
            log.warn("lms sso-callback unknown failure", exception);
            return redirect(frontendReturn("error", "lms_unknown"));
        }
    }

    private URI frontendReturn(String key, String value) {
        return URI.create(frontendOrigin + "/auth/return?" + key + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
