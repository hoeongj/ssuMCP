package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintGraduationConnector;
import com.ssuai.global.exception.SaintSessionExpiredException;

class SaintGraduationServiceTests {

    private final SaintGraduationConnector connector = mock(SaintGraduationConnector.class);
    private final SaintSessionStore sessionStore = mock(SaintSessionStore.class);
    private final SaintGraduationService service = new SaintGraduationService(connector, sessionStore);

    @Test
    void delegatesWithStoredSession() {
        PortalCookies cookies = new PortalCookies("session-json");
        when(sessionStore.cookies("20241234")).thenReturn(Optional.of(cookies));

        service.fetchGraduationRequirements("20241234");

        verify(connector).fetchGraduationRequirements("20241234", cookies);
    }

    @Test
    void missingSessionRaisesSessionExpiredWithoutCallingConnector() {
        when(sessionStore.cookies("20241234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetchGraduationRequirements("20241234"))
                .isInstanceOf(SaintSessionExpiredException.class);

        verifyNoInteractions(connector);
    }
}
