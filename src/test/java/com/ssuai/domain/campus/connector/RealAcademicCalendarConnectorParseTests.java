package com.ssuai.domain.campus.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;

class RealAcademicCalendarConnectorParseTests {

    private static final String FIXTURE = "fixtures/campus/academic_calendar_2026.html";

    @Test
    void parsesAllEventsForRequestedYear() throws IOException {
        Document doc = loadFixture(FIXTURE);

        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(doc, 2026);

        // Scoped to the calendar month blocks (not page-nav <li>s): 63 real events.
        assertThat(events).hasSize(63);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.date()).matches("2026-\\d{2}-\\d{2}");
            assertThat(event.event()).isNotBlank();
            assertThat(event.category()).isEmpty(); // real page carries no per-event category
        });
    }

    @Test
    void pinsKnownSingleDayEvent() throws IOException {
        Document doc = loadFixture(FIXTURE);

        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(doc, 2026);

        // "02.19 (목)" -> "2026학년도 1학기 수강신청(4학년)"
        assertThat(events).anySatisfy(event -> {
            assertThat(event.date()).isEqualTo("2026-02-19");
            assertThat(event.event()).contains("수강신청"); // 수강신청
        });
    }

    @Test
    void rangeEventCarriesStartAndInclusiveEndDate() throws IOException {
        Document doc = loadFixture(FIXTURE);

        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(doc, 2026);

        // "01.05 (월) ~ 01.28 (수)" range -> start + inclusive end.
        assertThat(events).anySatisfy(event -> {
            assertThat(event.date()).isEqualTo("2026-01-05");
            assertThat(event.endDate()).isEqualTo("2026-01-28");
        });
    }

    @Test
    void singleDayEventHasNullEndDate() throws IOException {
        Document doc = loadFixture(FIXTURE);

        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(doc, 2026);

        // "02.19 (목)" single-day row -> no end date.
        assertThat(events).anySatisfy(event -> {
            assertThat(event.date()).isEqualTo("2026-02-19");
            assertThat(event.endDate()).isNull();
        });
    }

    @Test
    void rangeCrossingNewYearRollsEndDateIntoNextYear() {
        // December block, "12.30 (수) ~ 01.02 (토)": the year-less end token has a
        // smaller month than the start, so the end must land in blockYear + 1.
        Document doc = Jsoup.parse(
                "<div id=\"calendar202612\" class=\"row\">"
                        + "<ul class=\"tb\"><li><div class=\"row\">"
                        + "<div class=\"col-xl-3 text-primary\">12.30 (수) ~ 01.02 (토)</div>"
                        + "<div class=\"col-xl-9\">동계 집중휴무</div>"
                        + "</div></li></ul></div>");

        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(doc, 2026);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().date()).isEqualTo("2026-12-30");
        assertThat(events.getFirst().endDate()).isEqualTo("2027-01-02");
    }

    @Test
    void filtersOutBlocksNotMatchingRequestedYear() throws IOException {
        Document doc = loadFixture(FIXTURE);

        // The fixture holds only 2026 month blocks; asking for 2025 must yield nothing
        // rather than 2026 data mislabeled as 2025 (guards a silently-ignored ?years= param).
        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(doc, 2025);

        assertThat(events).isEmpty();
    }

    @Test
    void monthAndDayComeFromDateTokenNotBlockId() {
        // Block id month is 03 but the date token says 05.10 — the token wins for month/day,
        // the block id supplies only the year. (Matters for ranges that cross a month boundary.)
        Document doc = Jsoup.parse(
                "<div id=\"calendar202603\" class=\"row\">"
                        + "<ul class=\"tb\"><li><div class=\"row\">"
                        + "<div class=\"col-xl-3 text-primary\">05.10 (월)</div>"
                        + "<div class=\"col-xl-9\">Test event</div>"
                        + "</div></li></ul></div>");

        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(doc, 2026);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().date()).isEqualTo("2026-05-10");
    }

    @Test
    void parseEmptyDocumentReturnsEmptyList() {
        Document emptyDoc = Jsoup.parse("<html><body></body></html>");

        List<AcademicCalendarEvent> events = RealAcademicCalendarConnector.parseEvents(emptyDoc, 2026);

        assertThat(events).isEmpty();
    }

    private static Document loadFixture(String resourcePath) throws IOException {
        try (InputStream in = RealAcademicCalendarConnectorParseTests.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("fixture not found: " + resourcePath);
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Jsoup.parse(html, "https://ssu.ac.kr");
        }
    }
}
