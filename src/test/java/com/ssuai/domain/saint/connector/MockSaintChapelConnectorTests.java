package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelInfo;

class MockSaintChapelConnectorTests {

    private static final Clock CLOCK_2026_05_16 = Clock.fixed(
            Instant.parse("2026-05-16T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void usesCurrentSemesterWhenSelectionIsOmitted() {
        MockSaintChapelConnector connector = new MockSaintChapelConnector(CLOCK_2026_05_16);

        ChapelInfo response = connector.fetchChapelInfo(
                "20241234", new PortalCookies("MYSAPSSO2=abc"), null, null);

        assertThat(response.year()).isEqualTo(2026);
        assertThat(response.semester()).isEqualTo("1학기");
        assertThat(response.attendances()).hasSize(2);
        assertThat(response.absenceAllowedCount()).isNull();
        assertThat(response.absenceApplications()).singleElement().satisfies(application -> {
            assertThat(application.category()).isEqualTo("병무관계");
            assertThat(application.reason()).isEqualTo("예비군");
            assertThat(application.status()).isEqualTo("승인");
        });
    }

    @Test
    void returnsRequestedSemester() {
        MockSaintChapelConnector connector = new MockSaintChapelConnector(CLOCK_2026_05_16);

        ChapelInfo response = connector.fetchChapelInfo(
                "20241234", new PortalCookies("MYSAPSSO2=abc"), 2025, "2학기");

        assertThat(response.year()).isEqualTo(2025);
        assertThat(response.semester()).isEqualTo("2학기");
    }
}
