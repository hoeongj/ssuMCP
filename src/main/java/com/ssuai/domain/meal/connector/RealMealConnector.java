package com.ssuai.domain.meal.connector;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector.SelectorParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.meal.dto.MealClosure;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.monitoring.AlertLevel;
import com.ssuai.global.monitoring.DiscordAlertService;

@Component
@ConditionalOnProperty(name = "ssuai.connector.meal", havingValue = "real")
class RealMealConnector implements MealConnector {

    private static final Logger log = LoggerFactory.getLogger(RealMealConnector.class);

    private static final String DEFAULT_MENU_URL = "https://soongguri.com/m/m_req/m_menu.php";
    private static final String USER_AGENT = "ssuAI/0.1 (+akftjdwn@gmail.com)";
    private static final String ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9";
    private static final String MENU_NAME_SELECTOR = "td.menu_nm";
    private static final String MENU_LIST_SELECTOR = "td.menu_list";
    private static final DateTimeFormatter MENU_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern COMMA_SPLIT_PATTERN = Pattern.compile("\\s*,\\s*");
    private static final Pattern LINE_SPLIT_PATTERN = Pattern.compile("\\R+");
    private static final Pattern PRICE_SUFFIX_PATTERN = Pattern.compile("\\s*-\\s*\\d+(?:\\.\\d+)?\\s*$");
    private static final Pattern STAR_PREFIX_PATTERN = Pattern.compile("^★\\s*");
    private static final Pattern TRAILING_DASH_PATTERN = Pattern.compile("\\s*-\\s*$");
    private static final Pattern HANGUL_PATTERN = Pattern.compile("[가-힣]");
    private static final List<String> METADATA_MARKERS = List.of(
            "*알러지유발식품", "알러지유발식품", "*원산지", "원산지");
    private static final long DEFAULT_MIN_INTERVAL_MS = 1_000L;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private final String menuUrl;
    private final long minIntervalMs;
    private final int timeoutMs;
    private final DiscordAlertService discordAlertService;
    private final ConcurrentMap<String, Object> rateLimitLocksByRcd = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastCallAtMsByRcd = new ConcurrentHashMap<>();

    RealMealConnector() {
        this(DEFAULT_MENU_URL, DEFAULT_MIN_INTERVAL_MS, DEFAULT_TIMEOUT_MS, null);
    }

    @Autowired
    RealMealConnector(DiscordAlertService discordAlertService) {
        this(DEFAULT_MENU_URL, DEFAULT_MIN_INTERVAL_MS, DEFAULT_TIMEOUT_MS, discordAlertService);
    }

    RealMealConnector(String menuUrl, long minIntervalMs, int timeoutMs) {
        this(menuUrl, minIntervalMs, timeoutMs, null);
    }

    RealMealConnector(String menuUrl, long minIntervalMs, int timeoutMs, DiscordAlertService discordAlertService) {
        this.menuUrl = Objects.requireNonNull(menuUrl);
        this.minIntervalMs = Math.max(0L, minIntervalMs);
        this.timeoutMs = timeoutMs;
        this.discordAlertService = discordAlertService;
    }

    @Override
    public MealResponse fetchMeal(LocalDate date, MealRestaurant restaurant) {
        long startedAt = System.currentTimeMillis();
        String displayName = restaurant.displayName();

        try {
            waitForRateLimit(restaurant.code());

            Document document = Jsoup.connect(buildMenuUrl(restaurant.code(), date))
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .get();

            List<MealItem> meals = parseMealItems(displayName, document, false);
            List<MealClosure> closures = parseClosures(displayName, document);

            if (meals.isEmpty() && closures.isEmpty()) {
                throw new ConnectorParseException();
            }

            log.debug("connector=meal status=ok restaurant={} date={} items={} closures={} ms={}",
                    displayName, date, meals.size(), closures.size(), elapsedMs(startedAt));
            return new MealResponse(date, meals, closures);
        } catch (SocketTimeoutException exception) {
            logFailure("timeout", displayName, date, startedAt);
            throw alert(new ConnectorTimeoutException(exception));
        } catch (HttpStatusException exception) {
            logFailure("http_" + exception.getStatusCode(), displayName, date, startedAt);
            throw alert(mapHttpStatus(exception));
        } catch (SelectorParseException exception) {
            logFailure("parse", displayName, date, startedAt);
            throw new ConnectorParseException(exception);
        } catch (ConnectorException exception) {
            logFailure(exception.getErrorCode().name().toLowerCase(Locale.ROOT),
                    displayName, date, startedAt);
            throw alert(exception);
        } catch (IOException exception) {
            if (isTimeout(exception)) {
                logFailure("timeout", displayName, date, startedAt);
                throw alert(new ConnectorTimeoutException(exception));
            }
            logFailure("unavailable", displayName, date, startedAt);
            throw alert(new ConnectorUnavailableException(exception));
        }
    }

    static MealResponse parse(LocalDate date, String restaurant, Document document) {
        return new MealResponse(date, parseMealItems(restaurant, document, true));
    }

    private static List<MealItem> parseMealItems(String restaurant, Document document, boolean failOnEmptyRows) {
        Elements nameCells = document.select(MENU_NAME_SELECTOR);
        if (nameCells.isEmpty()) {
            if (failOnEmptyRows) {
                throw new ConnectorParseException();
            }
            return List.of();
        }

        List<MealItem> meals = new ArrayList<>();
        for (Element nameCell : nameCells) {
            String corner = normalizeWhitespace(nameCell.text());
            Optional<MealType> mealType = mealTypeForCorner(corner);
            if (mealType.isEmpty()) {
                continue;
            }

            Element menuCell = nameCell.nextElementSibling();
            if (menuCell == null || !menuCell.is(MENU_LIST_SELECTOR)) {
                throw new ConnectorParseException();
            }
            if (isClosureReason(menuCell.text())) {
                continue;
            }

            List<String> menu = extractMenu(menuCell);
            if (!menu.isEmpty()) {
                meals.add(new MealItem(restaurant, mealType.get(), corner, menu));
            }
        }

        if (meals.isEmpty() && failOnEmptyRows) {
            throw new ConnectorParseException();
        }

        return List.copyOf(meals);
    }

    private static List<MealClosure> parseClosures(String restaurant, Document document) {
        Set<String> reasons = new LinkedHashSet<>();
        for (Element cell : document.select("tr > td[colspan=2], td.menu_list")) {
            String reason = normalizeWhitespace(cell.text());
            if (isClosureReason(reason)) {
                reasons.add(reason);
            }
        }
        return reasons.stream()
                .map(reason -> new MealClosure(restaurant, reason))
                .toList();
    }

    private String buildMenuUrl(String restaurantCode, LocalDate date) {
        String separator = menuUrl.contains("?") ? "&" : "?";
        return menuUrl + separator + "rcd=" + restaurantCode + "&sdt=" + MENU_DATE_FORMATTER.format(date);
    }

    private void waitForRateLimit(String rcd) {
        Object lock = rateLimitLocksByRcd.computeIfAbsent(rcd, key -> new Object());
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long lastCallAtMs = lastCallAtMsByRcd.getOrDefault(rcd, 0L);
            long waitMs = minIntervalMs - (now - lastCallAtMs);
            if (lastCallAtMs > 0L && waitMs > 0L) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ConnectorUnavailableException(exception);
                }
            }
            lastCallAtMsByRcd.put(rcd, System.currentTimeMillis());
        }
    }

    private static List<String> extractMenu(Element menuCell) {
        Set<String> items = new LinkedHashSet<>();

        for (Element element : menuCell.getAllElements()) {
            String ownText = element.wholeOwnText();
            if (containsMetadataMarker(ownText)) {
                break;
            }
            addMenuTokens(items, ownText);
        }

        return List.copyOf(items);
    }

    private static void addMenuTokens(Set<String> items, String rawText) {
        String text = stripMetadata(rawText);
        for (String rawToken : LINE_SPLIT_PATTERN.split(text)) {
            String token = cleanMenuToken(rawToken);
            for (String splitToken : splitMenuToken(token)) {
                if (shouldKeepMenuToken(splitToken)) {
                    items.add(splitToken);
                }
            }
        }
    }

    private static List<String> splitMenuToken(String token) {
        if (!token.contains(",")) {
            return List.of(token);
        }
        return COMMA_SPLIT_PATTERN.splitAsStream(token)
                .map(RealMealConnector::normalizeWhitespace)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String stripMetadata(String text) {
        int metadataIndex = -1;
        for (String marker : METADATA_MARKERS) {
            int index = text.indexOf(marker);
            if (index >= 0 && (metadataIndex < 0 || index < metadataIndex)) {
                metadataIndex = index;
            }
        }
        if (metadataIndex < 0) {
            return text;
        }
        return text.substring(0, metadataIndex);
    }

    private static String cleanMenuToken(String rawToken) {
        String token = STAR_PREFIX_PATTERN.matcher(normalizeWhitespace(rawToken)).replaceFirst("");
        token = PRICE_SUFFIX_PATTERN.matcher(token).replaceFirst("");
        token = TRAILING_DASH_PATTERN.matcher(token).replaceFirst("");
        return normalizeWhitespace(token);
    }

    private static boolean shouldKeepMenuToken(String token) {
        return !token.isBlank()
                && !token.startsWith("[")
                && !token.startsWith("*")
                && !token.contains("알러지")
                && !token.contains("원산지")
                && HANGUL_PATTERN.matcher(token).find();
    }

    private static boolean containsMetadataMarker(String text) {
        return METADATA_MARKERS.stream().anyMatch(text::contains);
    }

    private static boolean isClosureReason(String text) {
        return !text.isBlank()
                && (text.contains("쉽니다")
                || text.contains("휴무")
                || text.contains("어린이날")
                || text.contains("공휴일")
                || text.contains("운영하지")
                || text.contains("운영 안"));
    }

    private static Optional<MealType> mealTypeForCorner(String corner) {
        if (corner.equals("메뉴") || corner.contains("상시")) {
            return Optional.of(MealType.ALL_DAY);
        }
        if (corner.startsWith("조식")) {
            return Optional.of(MealType.BREAKFAST);
        }
        if (corner.startsWith("중식")) {
            return Optional.of(MealType.LUNCH);
        }
        if (corner.startsWith("석식")) {
            return Optional.of(MealType.DINNER);
        }
        return Optional.empty();
    }

    private static String normalizeWhitespace(String value) {
        return value.replace(' ', ' ')
                .replace('　', ' ')
                .replaceAll("\\s+", " ")
                .trim();
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

    private ConnectorException alert(ConnectorException exception) {
        if (discordAlertService == null) {
            return exception;
        }
        if (exception instanceof ConnectorTimeoutException) {
            discordAlertService.alertConnectorFailure(AlertLevel.ERROR, ErrorCode.CONNECTOR_TIMEOUT, exception);
        } else if (exception instanceof ConnectorUnavailableException) {
            discordAlertService.alertConnectorFailure(AlertLevel.ERROR, ErrorCode.CONNECTOR_UNAVAILABLE, exception);
        }
        return exception;
    }

    private static void logFailure(String reason, String restaurant, LocalDate date, long startedAt) {
        log.warn("connector=meal status=fail restaurant={} date={} reason={} ms={}",
                restaurant, date, reason, elapsedMs(startedAt));
    }
}
