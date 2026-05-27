package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScheduleResponse;

class MockSaintScheduleConnectorTests {

    private static final Clock CLOCK_2026_05_16 = Clock.fixed(
            Instant.parse("2026-05-16T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void iteratesEntireFourTermCycleFromEnrollmentYearForwardToCurrent() {
        MockSaintScheduleConnector connector = new MockSaintScheduleConnector(CLOCK_2026_05_16);

        ScheduleResponse response = connector.fetchSchedule("20221234",
                new PortalCookies("MYSAPSSO2=mock"));

        // (2026, 1학기) → (2022, 1학기) walking PREV through every term.
        // 4 years × 4 terms = 16 hops + the current term itself = 17.
        assertThat(response.enrollmentYear()).isEqualTo(2022);
        assertThat(response.currentYear()).isEqualTo(2026);
        assertThat(response.currentTerm()).isEqualTo(1);
        assertThat(response.terms()).hasSize(17);
        assertThat(response.terms().get(0).year()).isEqualTo(2026);
        assertThat(response.terms().get(0).term()).isEqualTo(1);
        // Last entry is the enrollment 1학기.
        assertThat(response.terms().get(16).year()).isEqualTo(2022);
        assertThat(response.terms().get(16).term()).isEqualTo(1);
        // Second entry is the step before — (2025, 겨울학기 = term 4).
        assertThat(response.terms().get(1).year()).isEqualTo(2025);
        assertThat(response.terms().get(1).term()).isEqualTo(4);
    }

    @Test
    void freshlyEnrolledStudentInSpringGetsOnlyTheCurrentTerm() {
        MockSaintScheduleConnector connector = new MockSaintScheduleConnector(CLOCK_2026_05_16);

        ScheduleResponse response = connector.fetchSchedule("20261234",
                new PortalCookies("MYSAPSSO2=mock"));

        assertThat(response.terms()).hasSize(1);
        assertThat(response.terms().get(0).year()).isEqualTo(2026);
        assertThat(response.terms().get(0).term()).isEqualTo(1);
    }

    @Test
    void cyclesThroughSummerWhenTodayIsInSummerWindow() {
        Clock summerClock = Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC);
        MockSaintScheduleConnector connector = new MockSaintScheduleConnector(summerClock);

        ScheduleResponse response = connector.fetchSchedule("20261234",
                new PortalCookies("MYSAPSSO2=mock"));

        // Enrolled 2026-1학기, currently 2026-여름학기 → 2 entries
        assertThat(response.currentTerm()).isEqualTo(2);
        assertThat(response.terms()).hasSize(2);
        assertThat(response.terms().get(0).term()).isEqualTo(2);
        assertThat(response.terms().get(1).term()).isEqualTo(1);
    }

    @Test
    void mockEntriesPopulateEveryTerm() {
        MockSaintScheduleConnector connector = new MockSaintScheduleConnector(CLOCK_2026_05_16);

        ScheduleResponse response = connector.fetchSchedule("20221234",
                new PortalCookies("MYSAPSSO2=mock"));

        assertThat(response.terms()).allSatisfy(term ->
                assertThat(term.entries()).isNotEmpty());
    }
}
