package com.ssuai.domain.auth.mcp;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.library.auth.LibraryCredentialLoginService;
import com.ssuai.domain.library.auth.dto.LibraryCredentialLoginRequest;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.response.ApiResponse;
import com.ssuai.global.response.ErrorResponse;

/**
 * MCP auth flow for the library (Task 18 Slice B+C).
 *
 * <p>GET /api/mcp/auth/library/start?state=...
 *   Redirects the browser to the frontend library login page at
 *   {@code {frontendOrigin}/mcp/auth/library?state=...}.
 *   The frontend page collects credentials and POSTs them to /callback.
 *
 * <p>POST /api/mcp/auth/library/callback
 *   Receives {state, loginId, password} from the frontend form.
 *   Consumes the one-time state, calls oasis.ssu.ac.kr via
 *   {@link LibraryCredentialLoginService}, stores the Pyxis token under
 *   a fresh opaque library session key, and links it to the MCP session.
 *
 * <p>Security:
 * <ul>
 *   <li>password (AES-encrypted by frontend) is never logged.
 *   <li>loginId (student number) is never logged.
 *   <li>state raw value is never logged.
 *   <li>Pyxis token stored in LibrarySessionStore under a random opaque key.
 *   <li>Response JSON never contains token, loginId, or principalKey.
 * </ul>
 */
@RestController
@RequestMapping("/api/mcp/auth/library")
public class McpLibraryAuthController {

    private static final Logger log = LoggerFactory.getLogger(McpLibraryAuthController.class);

    private final McpAuthService mcpAuthService;
    private final LibraryCredentialLoginService credentialLoginService;
    private final String frontendOrigin;

    public McpLibraryAuthController(
            McpAuthService mcpAuthService,
            LibraryCredentialLoginService credentialLoginService,
            @Value("${ssuai.frontend.origin:}") String frontendOrigin) {
        if (frontendOrigin == null || frontendOrigin.isBlank()) {
            throw new IllegalStateException(
                    "ssuai.frontend.origin (env: SSUAI_FRONTEND_ORIGIN) must be set; "
                            + "the library MCP auth flow cannot redirect to the login page without it.");
        }
        this.mcpAuthService = mcpAuthService;
        this.credentialLoginService = credentialLoginService;
        this.frontendOrigin = frontendOrigin;
    }

    /**
     * Redirects the browser to the frontend library login page with the state param.
     */
    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam String state) {
        String encoded = URLEncoder.encode(state, StandardCharsets.UTF_8);
        URI destination = URI.create(frontendOrigin + "/mcp/auth/library?state=" + encoded);
        log.debug("mcp library start: redirecting to frontend login page");
        return ResponseEntity.status(HttpStatus.FOUND).location(destination).build();
    }

    /**
     * Completes the library MCP login. Called by the frontend form at
     * {@code /mcp/auth/library} after the user enters their library credentials.
     *
     * <p>The {@code password} field must be AES-encrypted by the frontend,
     * matching the encryption that the oasis web client applies — this backend
     * passes it through without decrypting and never logs it.
     */
    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Void>> callback(
            @Valid @RequestBody McpLibraryCallbackRequest request) {

        McpAuthStateEntry entry = mcpAuthService.consumeState(request.state()).orElse(null);
        if (entry == null) {
            log.warn("mcp library callback: state invalid or expired");
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(new ErrorResponse("INVALID_STATE", "인증 요청이 만료되었거나 유효하지 않습니다. start_auth를 다시 호출해주세요.")));
        }
        if (entry.provider() != McpProviderType.LIBRARY) {
            log.warn("mcp library callback: provider mismatch expected=LIBRARY actual={}", entry.provider());
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(new ErrorResponse("PROVIDER_MISMATCH", "잘못된 인증 요청입니다. provider가 일치하지 않습니다.")));
        }

        // Generate an opaque library session key — this key indexes LibrarySessionStore,
        // completely separate from any web session id.
        String librarySessionKey = UUID.randomUUID().toString();
        try {
            credentialLoginService.login(librarySessionKey,
                    new LibraryCredentialLoginRequest(request.loginId(), request.password()));
        } catch (LibraryAuthRequiredException e) {
            log.info("mcp library callback: credential rejected session={}", entry.mcpSessionId().fingerprint());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.error(new ErrorResponse("AUTH_FAILED", "도서관 로그인에 실패했습니다. 학번과 비밀번호를 확인해주세요.")));
        } catch (Exception e) {
            log.warn("mcp library callback: unexpected error session={}", entry.mcpSessionId().fingerprint());
            return ResponseEntity.internalServerError().body(
                    ApiResponse.error(new ErrorResponse("SERVER_ERROR", "로그인 중 오류가 발생했습니다. 다시 시도해주세요.")));
        }

        mcpAuthService.linkProvider(entry.mcpSessionId(), McpProviderType.LIBRARY, librarySessionKey);
        log.debug("mcp library callback: linked session={}", entry.mcpSessionId().fingerprint());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Request body for the library credential login callback. */
    public record McpLibraryCallbackRequest(
            @jakarta.validation.constraints.NotBlank String state,
            @jakarta.validation.constraints.NotBlank String loginId,
            @jakarta.validation.constraints.NotBlank String password) {
    }
}
