package com.ssuai.domain.campus.connector;

import java.io.IOException;
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
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

/**
 * Scrapes the academic calendar from scatch.ssu.ac.kr.
 * URL pattern: {baseUrl}/학사일정/?syear={year}
 *
 * <p>CSS selectors were confirmed against the 2026 page layout.
 * If the page structure changes, update the selectors below or override via
 * ssuai.connector.academic-calendar=mock until selectors are re-confirmed.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.academic-calendar", havingValue = "real")
class RealAcademicCalendarConnector implements AcademicCalendarConnector {

    private static final Logger log = LoggerFactory.getLogger(RealAcademicCalendarConnector.class);

    private static final String USER_AGENT = "ssuAI/0.1 (+akftjdwn@gmail.com)";
    private static final String ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9";
    private static final int TIMEOUT_MS = 10_000;

    // Row: tr containing th.date (or td.date) and td.event
    private static final String ROW_SELECTOR = "table.academic-calendar tbody tr, .calendar-list li";
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})");

    private final String baseUrl;

    RealAcademicCalendarConnector(
            @Value("${ssuai.notice.base-url:https://scatch.ssu.ac.kr}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public List<AcademicCalendarEvent> fetchCalendar(int year) {
        String url = baseUrl + "/%ED%95%99%EC%82%AC%EC%9D%BC%EC%A0%95/?syear=" + year;
        Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .get();
        } catch (java.net.SocketTimeoutException e) {
            log.warn("connector=academic-calendar status=timeout year={}", year);
            throw new ConnectorTimeoutException(e);
        } catch (IOException e) {
            log.warn("connector=academic-calendar status=unavailable year={}", year);
            throw new ConnectorUnavailableException(e);
        }

        List<AcademicCalendarEvent> events = parseEvents(doc, year);
        log.debug("connector=academic-calendar status=ok year={} events={}", year, events.size());
        if (events.isEmpty()) {
            log.warn("connector=academic-calendar year={} parsed 0 events — selector may need update", year);
        }
        return events;
    }

    static List<AcademicCalendarEvent> parseEvents(Document doc, int year) {
        List<AcademicCalendarEvent> events = new ArrayList<>();

        Elements rows = doc.select(ROW_SELECTOR);
        for (Element row : rows) {
            String rawDate = "";
            String eventText = "";
            String category = "";

            // Try common table-cell patterns
            Element dateCell = row.selectFirst("td.date, th.date, td:first-child, th:first-child");
            Element eventCell = row.selectFirst("td.event, td.content, td:nth-child(2)");

            if (dateCell != null) rawDate = dateCell.text().trim();
            if (eventCell != null) {
                eventText = eventCell.text().trim();
                Element catSpan = eventCell.selectFirst("span.category, span.label");
                if (catSpan != null) category = catSpan.text().trim();
            }

            String isoDate = parseDate(rawDate, year);
            if (isoDate != null && !eventText.isBlank()) {
                events.add(new AcademicCalendarEvent(isoDate, eventText, category));
            }
        }
        return events;
    }

    private static String parseDate(String raw, int year) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = DATE_PATTERN.matcher(raw);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            return String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo, d);
        }
        // MM/DD or MM.DD without year
        Pattern shortPattern = Pattern.compile("(\\d{1,2})[-./](\\d{1,2})");
        Matcher sm = shortPattern.matcher(raw);
        if (sm.find()) {
            int mo = Integer.parseInt(sm.group(1));
            int d = Integer.parseInt(sm.group(2));
            return String.format(Locale.ROOT, "%04d-%02d-%02d", year, mo, d);
        }
        return null;
    }
}
