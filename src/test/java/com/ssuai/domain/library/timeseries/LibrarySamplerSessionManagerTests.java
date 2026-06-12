package com.ssuai.domain.library.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ssuai.domain.library.auth.LibraryCredentialLoginService;
import com.ssuai.domain.library.auth.LibraryPasswordEncryptor;
import com.ssuai.domain.library.auth.LibrarySessionStore;

class LibrarySamplerSessionManagerTests {

    @Test
    void loginForRunEncryptsRawPasswordAndStoresUnderInternalSessionKey() {
        LibrarySessionStore sessionStore = mock(LibrarySessionStore.class);
        LibraryCredentialLoginService credentialLoginService = mock(LibraryCredentialLoginService.class);
        LibrarySamplerSessionProperties properties = new LibrarySamplerSessionProperties();
        properties.setLoginId("20231234");
        properties.setPassword("sample-password");
        LibrarySamplerSessionManager manager = new LibrarySamplerSessionManager(
                sessionStore,
                credentialLoginService,
                new LibraryPasswordEncryptor(),
                properties);
        when(sessionStore.token(LibrarySamplerSessionManager.INTERNAL_SESSION_KEY))
                .thenReturn(Optional.of("stored-token"));

        Optional<String> token = manager.loginForRun();

        assertThat(token).contains("stored-token");
        ArgumentCaptor<String> encryptedPassword = ArgumentCaptor.forClass(String.class);
        verify(credentialLoginService).login(
                eq(LibrarySamplerSessionManager.INTERNAL_SESSION_KEY),
                eq("20231234"),
                encryptedPassword.capture());
        assertThat(encryptedPassword.getValue())
                .isEqualTo("NzdCn82E4c0LU+lR4E7qVQ==")
                .doesNotContain("sample-password");
    }

    @Test
    void missingCredentialsIsNoOp() {
        LibrarySessionStore sessionStore = mock(LibrarySessionStore.class);
        LibraryCredentialLoginService credentialLoginService = mock(LibraryCredentialLoginService.class);
        LibrarySamplerSessionManager manager = new LibrarySamplerSessionManager(
                sessionStore,
                credentialLoginService,
                new LibraryPasswordEncryptor(),
                new LibrarySamplerSessionProperties());

        assertThat(manager.hasCredentials()).isFalse();
        assertThat(manager.loginForRun()).isEmpty();
        verifyNoInteractions(credentialLoginService);
    }
}
