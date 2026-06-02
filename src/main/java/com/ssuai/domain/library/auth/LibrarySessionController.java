package com.ssuai.domain.library.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpStatus;
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

    public LibrarySessionController(
            LibrarySessionStore sessionStore,
            LibraryCredentialLoginService credentialLoginService) {
        this.sessionStore = sessionStore;
        this.credentialLoginService = credentialLoginService;
    }

    /** Credential login — frontend sends AES-encrypted password as oasis JS does. */
    @PostMapping("/api/library/login")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Login to oasis.ssu.ac.kr and store the resulting Pyxis-Auth-Token")
    public ApiResponse<Void> credentialLogin(
            @Valid @RequestBody LibraryCredentialLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String sessionKey = httpRequest.getSession().getId();
        credentialLoginService.login(sessionKey, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/api/library/session")
    @Operation(summary = "Clear the stored oasis.ssu.ac.kr Pyxis-Auth-Token for the current ssuAI session")
    public ApiResponse<Void> clearSession(HttpServletRequest httpRequest) {
        String sessionKey = httpRequest.getSession().getId();
        sessionStore.invalidate(sessionKey);
        log.info("library session cleared: sessionKey={}", LibrarySessionStore.fingerprint(sessionKey));
        return ApiResponse.success(null);
    }
}
