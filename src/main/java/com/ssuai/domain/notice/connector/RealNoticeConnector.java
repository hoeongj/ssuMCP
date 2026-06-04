package com.ssuai.domain.notice.connector;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeCategory;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.monitoring.AlertLevel;
import com.ssuai.global.monitoring.DiscordAlertService;

@Component
@ConditionalOnProperty(name = "ssuai.connector.notice", havingValue = "real")
class RealNoticeConnector implements NoticeConnector {

    private static final Logger log = LoggerFactory.getLogger(RealNoticeConnector.class);

    private static final String USER_AGENT = "ssuAI/0.1 (+akftjdwn@gmail.com)";
    private static final String ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9";

    // Default selectors — kept for package-private static test helpers and as fallback values.
    // Production paths read from NoticeConnectorProperties.getSelectors() so HTML-structure
    // changes can be patched via application.yml without a rebuild.
    static final String LIST_ITEM_SELECTOR = "ul.notice-lists > li:not(.notice_head)";
    static final String DATE_SELECTOR = "div.notice_col1 div.h2";
    static final String STATUS_SELECTOR = "div.notice_col2 span.tag";
    static final String TITLE_LINK_SELECTOR = "div.notice_col3 > a";
    static final String TITLE_TEXT_SELECTOR = "span.d-inline-blcok.m-pt-5";
    static final String CATEGORY_LABEL_SELECTOR = "span.label";
    static final String DEPARTMENT_SELECTOR = "div.notice_col4";
    static final String PAGINATION_SELECTOR = "nav.board-pagination a.page-numbers";
    static final String DETAIL_BODY_SELECTOR = "div.bg-white > hr + div";

    private static final int MAX_BODY_TEXT_LENGTH = 4000;

    private static final List<NoticeCategory> KNOWN_CATEGORIES = List.of(
            new NoticeCategory("학사", "학사"),
            new NoticeCategory("장학", "장학"),
            new NoticeCategory("국제교류", "국제교류"),
            new NoticeCategory("외국인유학생", "외국인유학생"),
            new NoticeCategory("채용", "채용"),
            new NoticeCategory("비교과·행사", "비교과·행사"),
            new NoticeCategory("교원채용", "교원채용"),
            new NoticeCategory("교직", "교직"),
            new NoticeCategory("봉사", "봉사"),
            new NoticeCategory("기타", "기타")
    );

    private final NoticeConnectorProperties properties;
    private final DiscordAlertService discordAlertService;

    RealNoticeConnector(NoticeConnectorProperties properties) {
        this(properties, null);
    }

    @Autowired
    RealNoticeConnector(NoticeConnectorProperties properties, DiscordAlertService discordAlertService) {
        this.properties = properties;
        this.discordAlertService = discordAlertService;
    }

    @Override
    public NoticeListResponse fetchNotices(String category, int page) {
        String url = buildListUrl(category, null, page);
        return fetchAndParseList(url, page);
    }

    @Override
    public NoticeListResponse searchNotices(String keyword, String category, int page) {
        String url = buildListUrl(category, keyword, page);
        return fetchAndParseList(url, page);
    }

    @Override
    public NoticeCategoriesResponse fetchCategories() {
        return new NoticeCategoriesResponse(KNOWN_CATEGORIES);
    }

    @Override
    public NoticeDetailResponse fetchDetail(String url) {
        long startedAt = System.currentTimeMillis();
        try {
            Document doc = connect(url);

            // Parse the notice metadata from the detail page
            String title = textOrEmpty(doc.selectFirst("h3"));
            String bodyElement = "";
            Element body = doc.selectFirst(properties.getSelectors().getDetailBody());
            if (body != null) {
                bodyElement = body.text();
            }
            String bodyText = truncate(bodyElement, MAX_BODY_TEXT_LENGTH);

            log.debug("connector=notice status=ok action=detail url={} ms={}",
                    url, elapsedMs(startedAt));

            return new NoticeDetailResponse(
                    title, url, "", "", "", "", bodyText
            );
        } catch (SocketTimeoutException exception) {
            logFailure("timeout", "detail", startedAt);
            throw alert(new ConnectorTimeoutException(exception));
        } catch (HttpStatusException exception) {
            logFailure("http_" + exception.getStatusCode(), "detail", startedAt);
            throw alert(mapHttpStatus(exception));
        } catch (ConnectorException exception) {
            logFailure(exception.getErrorCode().name().toLowerCase(Locale.ROOT), "detail", startedAt);
            throw alert(exception);
        } catch (IOException exception) {
            if (isTimeout(exception)) {
                logFailure("timeout", "detail", startedAt);
                throw alert(new ConnectorTimeoutException(exception));
            }
            logFailure("unavailable", "detail", startedAt);
            throw alert(new ConnectorUnavailableException(exception));
        }
    }

    private NoticeListResponse fetchAndParseList(String url, int page) {
        long startedAt = System.currentTimeMillis();
        try {
            Document doc = connect(url);
            List<Notice> notices = parseNoticeList(doc, properties.getSelectors());
            int totalPages = parseTotalPages(doc, properties.getSelectors().getPagination());

            log.debug("connector=notice status=ok action=list page={} items={} totalPages={} ms={}",
                    page, notices.size(), totalPages, elapsedMs(startedAt));

            return new NoticeListResponse(notices, page, totalPages);
        } catch (SocketTimeoutException exception) {
            logFailure("timeout", "list", startedAt);
            throw alert(new ConnectorTimeoutException(exception));
        } catch (HttpStatusException exception) {
            logFailure("http_" + exception.getStatusCode(), "list", startedAt);
            throw alert(mapHttpStatus(exception));
        } catch (ConnectorException exception) {
            logFailure(exception.getErrorCode().name().toLowerCase(Locale.ROOT), "list", startedAt);
            throw alert(exception);
        } catch (IOException exception) {
            if (isTimeout(exception)) {
                logFailure("timeout", "list", startedAt);
                throw alert(new ConnectorTimeoutException(exception));
            }
            logFailure("unavailable", "list", startedAt);
            throw alert(new ConnectorUnavailableException(exception));
        }
    }

    // package-private for testing — uses default (hardcoded) selectors
    static List<Notice> parseNoticeList(Document doc) {
        return parseNoticeList(doc, null);
    }

    static List<Notice> parseNoticeList(Document doc, NoticeConnectorProperties.Selectors sel) {
        String listItem = sel != null ? sel.getListItem() : LIST_ITEM_SELECTOR;
        String dateS = sel != null ? sel.getDate() : DATE_SELECTOR;
        String statusS = sel != null ? sel.getStatus() : STATUS_SELECTOR;
        String titleLink = sel != null ? sel.getTitleLink() : TITLE_LINK_SELECTOR;
        String titleText = sel != null ? sel.getTitleText() : TITLE_TEXT_SELECTOR;
        String categoryLabel = sel != null ? sel.getCategoryLabel() : CATEGORY_LABEL_SELECTOR;
        String dept = sel != null ? sel.getDepartment() : DEPARTMENT_SELECTOR;

        Elements items = doc.select(listItem);
        List<Notice> notices = new ArrayList<>();

        for (Element item : items) {
            String date = textOrEmpty(item.selectFirst(dateS));
            String status = textOrEmpty(item.selectFirst(statusS));

            Element linkElement = item.selectFirst(titleLink);
            String link = linkElement != null ? linkElement.attr("abs:href") : "";
            String title = "";
            String category = "";

            if (linkElement != null) {
                Element titleSpan = linkElement.selectFirst(titleText);
                title = textOrEmpty(titleSpan);
                Element categorySpan = linkElement.selectFirst(categoryLabel);
                category = textOrEmpty(categorySpan);
            }

            String department = textOrEmpty(item.selectFirst(dept));

            notices.add(new Notice(title, link, date, status, department, category));
        }

        return notices;
    }

    // package-private for testing — uses default selector
    static int parseTotalPages(Document doc) {
        return parseTotalPages(doc, PAGINATION_SELECTOR);
    }

    static int parseTotalPages(Document doc, String paginationSelector) {
        Elements pageLinks = doc.select(paginationSelector);
        int maxPage = -1;
        for (Element pageLink : pageLinks) {
            try {
                int pageNum = Integer.parseInt(pageLink.text().trim());
                if (pageNum > maxPage) {
                    maxPage = pageNum;
                }
            } catch (NumberFormatException ignored) {
                // skip non-numeric links like "next" arrows
            }
        }
        // Also check the current page span
        Element currentPage = doc.selectFirst("span.page-numbers.current");
        if (currentPage != null) {
            try {
                int current = Integer.parseInt(currentPage.text().trim());
                if (current > maxPage) {
                    maxPage = current;
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return maxPage;
    }

    private Document connect(String url) throws IOException {
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, properties.getTimeout().toMillis());
        try {
            long delayMs = ThreadLocalRandom.current().nextLong(300, 1200);
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(timeoutMs)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .get();
    }

    private String buildListUrl(String category, String keyword, int page) {
        StringBuilder sb = new StringBuilder(properties.getBaseUrl());
        sb.append("/%EA%B3%B5%EC%A7%80%EC%82%AC%ED%95%AD/?f");

        if (category != null && !category.isBlank()) {
            sb.append("&category=").append(encode(category));
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append("&keyword=").append(encode(keyword));
        }
        sb.append("&paged=").append(page);
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String textOrEmpty(Element element) {
        if (element == null) {
            return "";
        }
        return element.text().trim();
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
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

    private static void logFailure(String reason, String action, long startedAt) {
        log.warn("connector=notice status=fail action={} reason={} ms={}",
                action, reason, elapsedMs(startedAt));
    }
}
