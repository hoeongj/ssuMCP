package com.ssuai.domain.library.auth;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.ssuai.domain.library.auth.dto.LibraryCredentialLoginRequest;
import com.ssuai.global.exception.LibraryAuthRequiredException;

/**
 * Authenticates against oasis.ssu.ac.kr/pyxis-api/api/login using
 * the credentials supplied by the user. Extracts the {@code accessToken}
 * from the response and stores it in {@link LibrarySessionStore}.
 *
 * The {@code password} field arrives pre-encrypted by the ssuAI frontend
 * (same AES encoding the oasis web client applies). This service passes
 * it through without decrypting and never logs it.
 */
@Service
public class LibraryCredentialLoginService {

    private static final Logger log = LoggerFactory.getLogger(LibraryCredentialLoginService.class);

    private static final String LOGIN_PATH = "/pyxis-api/api/login";
    private static final String OASIS_REFERER = "https://oasis.ssu.ac.kr/login";
    private static final String OASIS_ORIGIN = "https://oasis.ssu.ac.kr";

    private final LibrarySessionStore sessionStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;

    public LibraryCredentialLoginService(
            LibrarySessionStore sessionStore,
            @Value("${ssuai.library.login.base-url:https://oasis.ssu.ac.kr}") String baseUrl) {
        this.sessionStore = sessionStore;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Calls pyxis-api login, stores the resulting accessToken under {@code sessionKey}.
     * Throws {@link LibraryAuthRequiredException} on invalid credentials.
     */
    public void login(String sessionKey, LibraryCredentialLoginRequest request) {
        login(sessionKey, request.loginId(), request.password());
    }

    /**
     * Calls pyxis-api login with a password already encrypted in the oasis format.
     */
    public void login(String sessionKey, String loginId, String encryptedPassword) {
        String accessToken = authenticate(loginId, encryptedPassword);
        bind(sessionKey, accessToken);
    }

    /**
     * Authenticates against pyxis-api and returns the captured {@code accessToken} WITHOUT
     * binding it to any session. Split out from {@link #login} so the servlet-session caller can
     * rotate its session id between a successful authentication and the session→token binding
     * (session-fixation hardening, Codex #8). Throws {@link LibraryAuthRequiredException} on
     * invalid credentials.
     */
    public String authenticate(String loginId, String encryptedPassword) {
        String body = callLogin(loginId, encryptedPassword);
        return extractAccessToken(body);
    }

    /**
     * Binds an already-authenticated {@code accessToken} to {@code sessionKey}. Kept separate
     * from {@link #authenticate} so the binding can target a freshly rotated session id.
     */
    public void bind(String sessionKey, String accessToken) {
        sessionStore.put(sessionKey, accessToken);
        log.info("library credential login ok: sessionKey={} tokenFp={}",
                LibrarySessionStore.fingerprint(sessionKey),
                LibrarySessionStore.fingerprint(accessToken));
    }

    private String callLogin(String loginId, String encryptedPassword) {
        try {
            record OasisLoginBody(String loginId, String password,
                                  boolean isFamilyLogin, boolean isMobile) {}
            return restClient.post()
                    .uri(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Referer", OASIS_REFERER)
                    .header("Origin", OASIS_ORIGIN)
                    .body(new OasisLoginBody(loginId, encryptedPassword, false, false))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.info("library credential login rejected: status={}", ex.getStatusCode().value());
            throw new LibraryAuthRequiredException();
        } catch (Exception ex) {
            log.warn("library credential login network error", ex);
            throw new LibraryAuthRequiredException();
        }
    }

    private String extractAccessToken(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.path("success").asBoolean(false)) {
                log.info("library credential login: success=false code={}",
                        root.path("code").asText());
                throw new LibraryAuthRequiredException();
            }
            String token = root.path("data").path("accessToken").asText(null);
            if (token == null || token.isBlank()) {
                log.warn("library credential login: accessToken missing in response");
                throw new LibraryAuthRequiredException();
            }
            return token;
        } catch (LibraryAuthRequiredException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("library credential login: response parse error", ex);
            throw new LibraryAuthRequiredException();
        }
    }
}
