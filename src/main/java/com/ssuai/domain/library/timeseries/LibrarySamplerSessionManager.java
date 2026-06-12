package com.ssuai.domain.library.timeseries;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.auth.LibraryCredentialLoginService;
import com.ssuai.domain.library.auth.LibraryPasswordEncryptor;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.global.exception.LibraryAuthRequiredException;

/**
 * Maintains the sampler-owned Pyxis session, isolated from user/MCP sessions.
 */
@Component
public class LibrarySamplerSessionManager {

    public static final String INTERNAL_SESSION_KEY = "internal:seat-sampler";

    private static final Logger log = LoggerFactory.getLogger(LibrarySamplerSessionManager.class);

    private final LibrarySessionStore sessionStore;
    private final LibraryCredentialLoginService credentialLoginService;
    private final LibraryPasswordEncryptor passwordEncryptor;
    private final LibrarySamplerSessionProperties properties;

    public LibrarySamplerSessionManager(
            LibrarySessionStore sessionStore,
            LibraryCredentialLoginService credentialLoginService,
            LibraryPasswordEncryptor passwordEncryptor,
            LibrarySamplerSessionProperties properties) {
        this.sessionStore = sessionStore;
        this.credentialLoginService = credentialLoginService;
        this.passwordEncryptor = passwordEncryptor;
        this.properties = properties;
    }

    public boolean hasCredentials() {
        return properties.hasCredentials();
    }

    public Optional<String> currentToken() {
        return sessionStore.token(INTERNAL_SESSION_KEY);
    }

    public void invalidateToken() {
        sessionStore.invalidate(INTERNAL_SESSION_KEY);
    }

    public Optional<String> loginForRun() {
        if (!hasCredentials()) {
            log.warn("library seat sampler login skipped: service credentials are not configured");
            return Optional.empty();
        }
        try {
            String encryptedPassword = passwordEncryptor.encrypt(properties.getPassword());
            credentialLoginService.login(INTERNAL_SESSION_KEY, properties.getLoginId(), encryptedPassword);
            return currentToken();
        } catch (LibraryAuthRequiredException exception) {
            log.warn("library seat sampler login rejected: sessionKey={}",
                    LibrarySessionStore.fingerprint(INTERNAL_SESSION_KEY));
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("library seat sampler login failed: sessionKey={}",
                    LibrarySessionStore.fingerprint(INTERNAL_SESSION_KEY), exception);
            return Optional.empty();
        }
    }
}
