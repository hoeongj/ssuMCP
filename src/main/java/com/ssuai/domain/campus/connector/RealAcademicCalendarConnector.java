package com.ssuai.domain.campus.connector;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

/**
 * Scrapes the official academic calendar from the main university site.
 *
 * <p>Source: {@code https://ssu.ac.kr/학사/학사일정/?years={year}} — the calendar is
 * server-rendered (no JS/AJAX/iframe) as a list of month blocks. Each month block is
 * {@code <div id="calendarYYYYMM">} carrying the year+month, and each event is a
 * {@code <ul class="tb"> > <li> > <div class="row">} row with a date cell
 * ({@code .text-primary}, format {@code "MM.DD (요일)"} or a {@code "~"} range) and a
 * title cell. The published range is 2019-01 .. 2027-02; the {@code ?years=} parameter
 * selects the year (the prev/next links on the page use it).
 *
 * <p>Selectors and the URL were confirmed against the live 2025/2026/2027 pages
 * (fetched with this connector's User-Agent — the site does not block it). The page
 * carries no per-event category, so {@link AcademicCalendarEvent#category()} is always
 * empty for real data. If the page structure changes, override via
 * {@code ssuai.connector.academic-calendar=mock} until selectors are re-confirmed.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.academic-calendar", havingValue = "real")
class RealAcademicCalendarConnector implements AcademicCalendarConnector {

    private static final Logger log = LoggerFactory.getLogger(RealAcademicCalendarConnector.class);

    private static final String USER_AGENT = "ssuAI/0.1 (+akftjdwn@gmail.com)";
    private static final String ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9";
    private static final int TIMEOUT_MS = 10_000;

    // URL-encoded "/학사/학사일정/".
    private static final String CALENDAR_PATH =
            "/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%9D%BC%EC%A0%95/";

    // Month block: <div id="calendarYYYYMM" ...>. The 6 trailing digits are year+month.
    private static final String MONTH_BLOCK_SELECTOR = "div[id~=^calendar\\d{6}$]";
    // Date token inside the date cell, e.g. "02.19" — month+day, no year.
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})");

    private final String baseUrl;

    RealAcademicCalendarConnector(
            @Value("${ssuai.academic-calendar.base-url:https://ssu.ac.kr}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public List<AcademicCalendarEvent> fetchCalendar(int year) {
        String url = baseUrl + CALENDAR_PATH + "?years=" + year;
        Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .get();
        } catch (SocketTimeoutException e) {
            log.warn("connector=academic-calendar status=timeout year={}", year);
            throw new ConnectorTimeoutException(e);
        } catch (IOException e) {
            log.warn("connector=academic-calendar status=unavailable year={}", year);
            throw new ConnectorUnavailableException(e);
        }

        List<AcademicCalendarEvent> events = parseEvents(doc, year);
        if (events.isEmpty()) {
            // Do NOT silently fall back to mock — an empty real result must surface, not be
            // masked by fabricated data. Year out of published range also lands here.
            log.warn("connector=academic-calendar year={} parsed 0 events — "
                    + "year out of published range or selectors need update", year);
        } else {
            log.debug("connector=academic-calendar status=ok year={} events={}", year, events.size());
        }
        return events;
    }

    /**
     * Parses month blocks into events. Only blocks whose id-year equals {@code year} are
     * kept: if the site ever ignores {@code ?years=} (as the old {@code ?syear=} param was
     * silently ignored), this yields an empty list rather than current-year data mislabeled
     * as the requested year.
     */
    static List<AcademicCalendarEvent> parseEvents(Document doc, int year) {
        List<AcademicCalendarEvent> events = new ArrayList<>();

        Elements monthBlocks = doc.select(MONTH_BLOCK_SELECTOR);
        for (Element block : monthBlocks) {
            // id = "calendarYYYYMM"; the year anchors the year-less "MM.DD" date cells.
            int blockYear = Integer.parseInt(block.id().substring("calendar".length(), "calendar".length() + 4));
            if (blockYear != year) {
                continue;
            }

            for (Element li : block.select("ul.tb > li")) {
                Element row = li.selectFirst("div.row");
                if (row == null) {
                    continue;
                }
                Element dateCell = row.selectFirst("div.text-primary");
                if (dateCell == null) {
                    continue;
                }
                Element titleCell = null;
                for (Element child : row.children()) {
                    if (child != dateCell) {
                        titleCell = child;
                        break;
                    }
                }
                if (titleCell == null) {
                    continue;
                }

                String title = titleCell.text().trim();
                // The date cell carries month+day; the year comes from the block id.
                String isoDate = parseStartDate(dateCell.text(), blockYear);
                if (isoDate != null && !title.isBlank()) {
                    events.add(new AcademicCalendarEvent(isoDate, title, ""));
                }
            }
        }
        return events;
    }

    /**
     * Resolves the event's start date. The cell text is "MM.DD (요일)" for a single day or
     * "MM.DD (요일) ~ MM.DD (요일)" for a range; either way the first token is the start.
     * Month and day come from the token, the year from the enclosing month block.
     */
    private static String parseStartDate(String cellText, int year) {
        if (cellText == null || cellText.isBlank()) {
            return null;
        }
        Matcher m = MONTH_DAY.matcher(cellText);
        if (!m.find()) {
            return null;
        }
        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }
}
