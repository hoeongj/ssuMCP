package com.ssuai.domain.dorm.connector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector.SelectorParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.meal.dto.MealClosure;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.dorm-meal", havingValue = "real")
class RealDormMealConnector implements DormMealConnector {

    private static final Logger log = LoggerFactory.getLogger(RealDormMealConnector.class);

    private static final String DEFAULT_PAGE_URL =
            "https://ssudorm.ssu.ac.kr:444/SShostel/mall_main.php?viewform=B0001_foodboard_list&board_no=1";
    private static final String USER_AGENT = "ssuAI/0.1 (+akftjdwn@gmail.com)";
    private static final String ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9";
    private static final String CHARSET = "EUC-KR";
    static final String RESTAURANT = "레지던스홀 기숙사 식당";

    private static final long DEFAULT_MIN_INTERVAL_MS = 1_000L;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern BR_PATTERN = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern LINE_SPLIT_PATTERN = Pattern.compile("\\R+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.KOREAN);

    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec(0, MealType.BREAKFAST, "조식"),
            new ColumnSpec(1, MealType.LUNCH, "중식"),
            new ColumnSpec(2, MealType.DINNER, "석식"));

    private static final List<String> CLOSURE_KEYWORDS = List.of(
            "미운영", "휴무", "쉽니다", "공휴일", "운영하지", "운영 안", "어린이날");

    private final String pageUrl;
    private final long minIntervalMs;
    private final int timeoutMs;
    private long lastCallAtMs;

    RealDormMealConnector() {
        this(DEFAULT_PAGE_URL, DEFAULT_MIN_INTERVAL_MS, DEFAULT_TIMEOUT_MS);
    }

    RealDormMealConnector(String pageUrl, long minIntervalMs, int timeoutMs) {
        this.pageUrl = Objects.requireNonNull(pageUrl);
        this.minIntervalMs = Math.max(0L, minIntervalMs);
        this.timeoutMs = timeoutMs;
    }

    @Override
    public WeeklyMealResponse fetchThisWeekMeal() {
        long startedAt = System.currentTimeMillis();

        try {
            waitForRateLimit();

            Connection.Response response = Jsoup.connect(pageUrl)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .ignoreContentType(true)
                    .execute();

            Document document = Jsoup.parse(
                    new ByteArrayInputStream(response.bodyAsBytes()),
                    CHARSET,
                    pageUrl);

            WeeklyMealResponse result = parse(document);
            log.debug("connector=dorm-meal status=ok startDate={} endDate={} days={} ms={}",
                    result.startDate(), result.endDate(), result.days().size(), elapsedMs(startedAt));
            return result;
        } catch (SocketTimeoutException exception) {
            logFailure("timeout", startedAt);
            throw new ConnectorTimeoutException(exception);
        } catch (HttpStatusException exception) {
            logFailure("http_" + exception.getStatusCode(), startedAt);
            throw mapHttpStatus(exception);
        } catch (SelectorParseException exception) {
            logFailure("parse", startedAt);
            throw new ConnectorParseException(exception);
        } catch (ConnectorException exception) {
            logFailure(exception.getErrorCode().name().toLowerCase(Locale.ROOT), startedAt);
            throw exception;
        } catch (IOException exception) {
            if (isTimeout(exception)) {
                logFailure("timeout", startedAt);
                throw new ConnectorTimeoutException(exception);
            }
            logFailure("unavailable", startedAt);
            throw new ConnectorUnavailableException(exception);
        }
    }

    static WeeklyMealResponse parse(Document document) {
        Elements rows = document.select("table.boxstyle02 tbody tr");
        if (rows.isEmpty()) {
            throw new ConnectorParseException();
        }

        List<MealResponse> days = new ArrayList<>();
        LocalDate startDate = null;
        LocalDate endDate = null;

        for (Element row : rows) {
            Element header = row.selectFirst("th");
            if (header == null) {
                continue;
            }
            LocalDate date = parseDate(header.text());
            if (date == null) {
                continue;
            }

            Elements cells = row.select("td");
            if (cells.isEmpty()) {
                continue;
            }

            List<MealItem> meals = new ArrayList<>();
            List<MealClosure> closures = new ArrayList<>();

            for (ColumnSpec column : COLUMNS) {
                if (column.index() >= cells.size()) {
                    continue;
                }
                String cellText = cellText(cells.get(column.index()));
                if (cellText.isBlank()) {
                    continue;
                }
                if (isClosureMarker(cellText)) {
                    closures.add(new MealClosure(RESTAURANT, column.cornerName() + " " + cleanClosureReason(cellText)));
                } else {
                    List<String> menu = splitMenu(cellText);
                    if (!menu.isEmpty()) {
                        meals.add(new MealItem(RESTAURANT, column.mealType(), column.cornerName(), menu));
                    }
                }
            }

            days.add(new MealResponse(date, List.copyOf(meals), List.copyOf(closures)));
            if (startDate == null || date.isBefore(startDate)) {
                startDate = date;
            }
            if (endDate == null || date.isAfter(endDate)) {
                endDate = date;
            }
        }

        if (days.isEmpty() || startDate == null) {
            throw new ConnectorParseException();
        }

        return new WeeklyMealResponse(startDate, endDate, List.copyOf(days));
    }

    private static String cellText(Element cell) {
        String html = BR_PATTERN.matcher(cell.html()).replaceAll("\n");
        Element fragment = Jsoup.parseBodyFragment(html).body();
        return fragment.wholeText().trim();
    }

    private static List<String> splitMenu(String text) {
        return LINE_SPLIT_PATTERN.splitAsStream(text)
                .map(RealDormMealConnector::normalizeWhitespace)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static boolean isClosureMarker(String text) {
        for (String keyword : CLOSURE_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String cleanClosureReason(String text) {
        return normalizeWhitespace(text);
    }

    private static LocalDate parseDate(String headerText) {
        Matcher matcher = DATE_PATTERN.matcher(headerText);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1), DATE_FORMATTER);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private synchronized void waitForRateLimit() {
        long now = System.currentTimeMillis();
        long waitMs = minIntervalMs - (now - lastCallAtMs);
        if (lastCallAtMs > 0L && waitMs > 0L) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ConnectorUnavailableException(exception);
            }
        }
        lastCallAtMs = System.currentTimeMillis();
    }

    private static ConnectorException mapHttpStatus(HttpStatusException exception) {
        int statusCode = exception.getStatusCode();
        if (statusCode == 408 || statusCode == 504) {
            return new ConnectorTimeoutException(exception);
        }
        return new ConnectorUnavailableException(exception);
    }

    private static boolean isTimeout(IOException exception) {
        String message = exception.getMessage();
        return exception instanceof InterruptedIOException
                || (message != null && message.toLowerCase(Locale.ROOT).contains("timed out"));
    }

    private static long elapsedMs(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private static void logFailure(String reason, long startedAt) {
        log.warn("connector=dorm-meal status=fail reason={} ms={}", reason, elapsedMs(startedAt));
    }

    private static String normalizeWhitespace(String text) {
        return WHITESPACE_PATTERN.matcher(text.replace(' ', ' ').replace('　', ' ')).replaceAll(" ").trim();
    }

    private record ColumnSpec(int index, MealType mealType, String cornerName) {
    }
}
