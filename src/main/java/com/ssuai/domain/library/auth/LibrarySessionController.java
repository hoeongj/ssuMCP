package com.ssuai.domain.library.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.library.auth.dto.LibraryCredentialLoginRequest;
import com.ssuai.global.response.ApiResponse;

@RestController
@Tag(name = "Library", description = "Library upstream session capture")
public class LibrarySessionController {

    private static final Logger log = LoggerFactory.getLogger(LibrarySessionController.class);

    private final LibrarySessionStore sessionStore;
    private final LibraryCredentialLoginService credentialLoginService;
    private final LibrarySessionProperties properties;
    private final LibrarySessionKeyResolver sessionKeyResolver;

    public LibrarySessionController(
            LibrarySessionStore sessionStore,
            LibraryCredentialLoginService credentialLoginService,
            LibrarySessionProperties properties,
            LibrarySessionKeyResolver sessionKeyResolver) {
        this.sessionStore = sessionStore;
        this.credentialLoginService = credentialLoginService;
        this.properties = properties;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    /**
     * Credential login — frontend sends AES-encrypted password as oasis JS does.
     *
     * <p>The store key is a freshly generated, server-side random value (ADR 0096) — it is
     * never derived from (or bound to) the Tomcat servlet session, so the library login
     * survives a backend redeploy or a pod switch across replicas, both of which used to orphan
     * the old in-memory JSESSIONID binding. There is no session-fixation risk to harden against
     * here: the key is minted on this request and never accepted from the client, so
     * {@code changeSessionId()} is obsolete for this flow.
     */
    @PostMapping("/api/library/login")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Login to oasis.ssu.ac.kr and store the resulting Pyxis-Auth-Token")
    public ApiResponse<Void> credentialLogin(
            @Valid @RequestBody LibraryCredentialLoginRequest request,
            HttpServletResponse httpResponse
    ) {
        String accessToken = credentialLoginService.authenticate(request.loginId(), request.password());
        String sessionKey = UUID.randomUUID().toString();
        credentialLoginService.bind(sessionKey, accessToken);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, buildSessionCookie(sessionKey).toString());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/api/library/session")
    @Operation(summary = "Clear the stored oasis.ssu.ac.kr Pyxis-Auth-Token for the current library session")
    public ApiResponse<Void> clearSession(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Optional<String> sessionKey = sessionKeyResolver.resolve(httpRequest);
        sessionKey.ifPresent(key -> {
            sessionStore.invalidate(key);
            log.info("library session cleared: sessionKey={}", LibrarySessionStore.fingerprint(key));
        });
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, clearedSessionCookie().toString());
        return ApiResponse.success(null);
    }

    private ResponseCookie buildSessionCookie(String sessionKey) {
        LibrarySessionProperties.SessionCookie cookieConfig = properties.getCookie();
        return ResponseCookie.from(cookieConfig.getName(), sessionKey)
                .httpOnly(true)
                .secure(cookieConfig.isSecure())
                .sameSite(cookieConfig.getSameSite())
                .path(cookieConfig.getPath())
                .maxAge(properties.getTtl())
                .build();
    }

    private ResponseCookie clearedSessionCookie() {
        LibrarySessionProperties.SessionCookie cookieConfig = properties.getCookie();
        return ResponseCookie.from(cookieConfig.getName(), "")
                .httpOnly(true)
                .secure(cookieConfig.isSecure())
                .sameSite(cookieConfig.getSameSite())
                .path(cookieConfig.getPath())
                .maxAge(Duration.ZERO)
                .build();
    }
}
