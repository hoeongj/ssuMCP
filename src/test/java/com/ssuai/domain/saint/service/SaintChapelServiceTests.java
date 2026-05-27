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
import com.ssuai.domain.saint.connector.SaintChapelConnector;
import com.ssuai.global.exception.UnauthorizedException;

class SaintChapelServiceTests {

    private final SaintChapelConnector connector = mock(SaintChapelConnector.class);
    private final SaintSessionStore sessionStore = mock(SaintSessionStore.class);
    private final SaintChapelService service = new SaintChapelService(connector, sessionStore);

    @Test
    void normalizesSemesterBeforeDelegating() {
        PortalCookies cookies = new PortalCookies("session-json");
        when(sessionStore.cookies("20241234")).thenReturn(Optional.of(cookies));

        service.fetchChapelInfo("20241234", 2025, "two");

        verify(connector).fetchChapelInfo("20241234", cookies, 2025, "2학기");
    }

    @Test
    void invalidSemesterFailsBeforeAccessingSession() {
        assertThatThrownBy(() -> service.fetchChapelInfo("20241234", 2025, "spring"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(sessionStore, connector);
    }

    @Test
    void blankStudentIdRaisesUnauthorized() {
        assertThatThrownBy(() -> service.fetchChapelInfo(" ", null, null))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(sessionStore, connector);
    }
}
