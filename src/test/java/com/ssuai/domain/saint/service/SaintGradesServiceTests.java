package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintGradesConnector;
import com.ssuai.domain.saint.dto.GpaSummary;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.TermGpa;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

class SaintGradesServiceTests {

    private final SaintGradesConnector connector = mock(SaintGradesConnector.class);
    private final SaintSessionStore sessionStore = mock(SaintSessionStore.class);
    private final SaintGradesService service = new SaintGradesService(connector, sessionStore);

    @Test
    void happyPathReadsCookiesAndDelegatesToConnector() {
        PortalCookies cookies = new PortalCookies("MYSAPSSO2=abc");
        GpaSummary summary = new GpaSummary(75.0d, 75.0d, 262.5d, 3.5d, 85.0d, 12.0d);
        GradesResponse stub = new GradesResponse(
                List.of(new TermGpa(2026, "1학기", 18.0d, 18.0d, 3.0d, 3.5d, 63.0d, 85.0d,
                        "50/100", "60/100", false, false, false)),
                summary, summary, Map.of());
        when(sessionStore.cookies("20241234")).thenReturn(Optional.of(cookies));
        when(connector.fetchGrades("20241234", cookies)).thenReturn(stub);

        GradesResponse result = service.fetchGrades("20241234");

        assertThat(result).isSameAs(stub);
        verify(connector).fetchGrades("20241234", cookies);
    }

    @Test
    void missingCookiesRaiseSaintSessionExpired() {
        when(sessionStore.cookies("20241234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetchGrades("20241234"))
                .isInstanceOf(SaintSessionExpiredException.class);

        verify(connector, never()).fetchGrades(any(), any());
    }

    @Test
    void connectorMayItselfRaiseSaintSessionExpiredAndItPropagates() {
        PortalCookies cookies = new PortalCookies("MYSAPSSO2=stale");
        when(sessionStore.cookies("20241234")).thenReturn(Optional.of(cookies));
        when(connector.fetchGrades(eq("20241234"), any()))
                .thenThrow(new SaintSessionExpiredException("upstream gate"));

        assertThatThrownBy(() -> service.fetchGrades("20241234"))
                .isInstanceOf(SaintSessionExpiredException.class);
    }

    @Test
    void blankStudentIdRaisesUnauthorizedBeforeTouchingStore() {
        assertThatThrownBy(() -> service.fetchGrades(null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.fetchGrades(""))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.fetchGrades("   "))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(sessionStore, connector);
    }
}
