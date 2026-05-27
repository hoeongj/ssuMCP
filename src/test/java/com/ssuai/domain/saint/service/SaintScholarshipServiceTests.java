package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintScholarshipConnector;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

class SaintScholarshipServiceTests {

    private final SaintScholarshipConnector connector = mock(SaintScholarshipConnector.class);
    private final SaintSessionStore sessionStore = mock(SaintSessionStore.class);
    private final SaintScholarshipService service = new SaintScholarshipService(connector, sessionStore);

    @Test
    void filtersEntriesByRequestedYear() {
        PortalCookies cookies = new PortalCookies("session-json");
        when(sessionStore.cookies("20241234")).thenReturn(Optional.of(cookies));
        when(connector.fetchScholarships("20241234", cookies)).thenReturn(List.of(
                new ScholarshipEntry(2025, "2학기", "A", 1, "지급", "완료"),
                new ScholarshipEntry(2024, "1학기", "B", 2, "지급", "완료")));

        List<ScholarshipEntry> result = service.fetchScholarships("20241234", 2025);

        assertThat(result).extracting(ScholarshipEntry::year).containsExactly(2025);
    }

    @Test
    void yearWithoutMatchesReturnsEmptyList() {
        PortalCookies cookies = new PortalCookies("session-json");
        when(sessionStore.cookies("20241234")).thenReturn(Optional.of(cookies));
        when(connector.fetchScholarships("20241234", cookies)).thenReturn(List.of(
                new ScholarshipEntry(2024, "1학기", "B", 2, "지급", "완료")));

        assertThat(service.fetchScholarships("20241234", 2025)).isEmpty();
    }

    @Test
    void invalidYearFailsBeforeAccessingSession() {
        assertThatThrownBy(() -> service.fetchScholarships("20241234", 0))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(sessionStore, connector);
    }
}
