package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GradesResponse;

class MockSaintGradesConnectorTests {

    private static final Clock CLOCK_2026_05_16 = Clock.fixed(
            Instant.parse("2026-05-16T03:00:00Z"), ZoneOffset.UTC);

    private final PortalCookies cookies = new PortalCookies("MYSAPSSO2=abc");

    @Test
    void historyCoversEnrollmentYearThroughCurrentTerm() {
        MockSaintGradesConnector connector = new MockSaintGradesConnector(CLOCK_2026_05_16);

        GradesResponse response = connector.fetchGrades("20241234", cookies);

        // 2026-1 (current, P/F) + 2025-{2,1} + 2024-{2,1} = 5 rows
        assertThat(response.history()).hasSize(5);
        assertThat(response.history().get(0).year()).isEqualTo(2026);
        assertThat(response.history().get(0).term()).isEqualTo("1학기");
        assertThat(response.history().get(0).gpa()).isEqualTo(0.0d);
        assertThat(response.history().get(4).year()).isEqualTo(2024);
        assertThat(response.history().get(4).term()).isEqualTo("1학기");
    }

    @Test
    void cumulativeSummariesArePresentAndDistinct() {
        MockSaintGradesConnector connector = new MockSaintGradesConnector(CLOCK_2026_05_16);

        GradesResponse response = connector.fetchGrades("20221234", cookies);

        assertThat(response.academicRecord().requestedCredits()).isEqualTo(75.0d);
        assertThat(response.certificate().requestedCredits()).isEqualTo(72.0d);
        assertThat(response.academicRecord().gpa()).isEqualTo(3.50d);
    }

    @Test
    void priorTermsCarryDetailRowsCurrentTermDoesNot() {
        MockSaintGradesConnector connector = new MockSaintGradesConnector(CLOCK_2026_05_16);

        GradesResponse response = connector.fetchGrades("20221234", cookies);

        String currentKey = response.history().get(0).termKey();
        assertThat(response.detailsByTerm()).doesNotContainKey(currentKey);
        String priorKey = response.history().get(1).termKey();
        assertThat(response.detailsByTerm().get(priorKey)).isNotEmpty();
    }

    @Test
    void freshmanStudentStillReturnsCurrentTermOnly() {
        MockSaintGradesConnector connector = new MockSaintGradesConnector(CLOCK_2026_05_16);

        GradesResponse response = connector.fetchGrades("20261234", cookies);

        assertThat(response.history()).hasSize(1);
        assertThat(response.detailsByTerm()).isEmpty();
    }
}
