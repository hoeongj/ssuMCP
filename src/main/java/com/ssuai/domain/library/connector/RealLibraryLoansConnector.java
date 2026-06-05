package com.ssuai.domain.library.connector;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.ssuai.domain.library.dto.LibraryLoanItem;
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.exception.LibraryAuthRequiredException;

/**
 * Fetches the authenticated user's current library loans from
 * oasis.ssu.ac.kr via GET /pyxis-api/1/api/charges?offset=0&max=20.
 * Returns empty list when the upstream responds with success.noRecord.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.library-loans", havingValue = "real")
public class RealLibraryLoansConnector implements LibraryLoansConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLibraryLoansConnector.class);

    private static final String LOANS_PATH = "/pyxis-api/1/api/charges?offset=0&max=20";
    private static final String LOANS_REFERER = "https://oasis.ssu.ac.kr/mylibrary/charge/charges";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String NEED_LOGIN_CODE = "error.authentication.needLogin";
    private static final String NO_RECORD_CODE = "success.noRecord";

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RealLibraryLoansConnector(
            LibrarySeatProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("librarySeatRestClient") RestClient restClient
    ) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public LibraryLoansResponse fetchLoans(String token) {
        String body = callUpstream(token);
        return parseBody(body);
    }

    private String callUpstream(String token) {
        randomDelay();
        try {
            return restClient.get()
                    .uri(LOANS_PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Pyxis-Auth-Token", token != null ? token : "")
                    .header("Referer", LOANS_REFERER)
                    .header("Accept-Language", "ko")
                    .header("User-Agent", BROWSER_UA)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException exception) {
            log.warn("library loans connector timeout/io");
            throw alert(new ConnectorTimeoutException(exception));
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            log.warn("library loans connector http error: status={}", status.value());
            if (status.is5xxServerError()) {
                throw alert(new ConnectorUnavailableException(exception));
            }
            throw new ConnectorParseException(exception);
        }
    }

    private static ConnectorException alert(ConnectorException exception) {
        return exception;
    }

    private static void randomDelay() {
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 1200));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private LibraryLoansResponse parseBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ConnectorParseException();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new ConnectorParseException(exception);
        }

        String code = root.path("code").asText("");
        if (NO_RECORD_CODE.equals(code)) {
            return new LibraryLoansResponse(0, List.of());
        }

        if (!root.path("success").asBoolean(false)) {
            if (NEED_LOGIN_CODE.equals(code)) {
                log.info("library loans upstream returned needLogin — token expired or invalid");
                throw new LibraryAuthRequiredException();
            }
            log.warn("library loans upstream returned success=false: code={}", code);
            throw new ConnectorParseException();
        }

        JsonNode data = root.path("data");
        int total = data.path("totalCount").asInt(0);
        JsonNode list = data.path("list");

        List<LibraryLoanItem> items = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode entry : list) {
                items.add(toLoanItem(entry));
            }
        }
        return new LibraryLoansResponse(total, items);
    }

    private static LibraryLoanItem toLoanItem(JsonNode entry) {
        long id = entry.path("id").asLong(0L);
        JsonNode biblio = entry.path("biblio");
        String title = textOr(firstPresent(biblio.path("titleStatement"), entry.path("title")), "(제목 미상)");
        String author = textOr(firstPresent(biblio.path("author"), entry.path("author")), null);
        String callNumber = textOr(firstPresent(entry.path("callNo"), entry.path("callNumber")), null);
        LocalDate loanDate = parseDate(textOr(firstPresent(entry.path("chargeDate"), entry.path("loanDate")), null));
        LocalDate dueDate  = parseDate(textOr(firstPresent(entry.path("dueDate"), entry.path("returnDate")), null));
        boolean overdue    = entry.path("isOverdue").asBoolean(entry.path("overdueDays").asInt(0) > 0);
        boolean renewable  = entry.path("isRenewable").asBoolean(entry.path("isRenewed").asBoolean(false));
        return new LibraryLoanItem(id, title, author, callNumber, loanDate, dueDate, overdue, renewable);
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            // Pyxis dates: "2026-05-10" or "2026-05-10T00:00:00" — take first 10 chars
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        String text = node.asText("");
        return text.isBlank() ? fallback : text;
    }

    private static JsonNode firstPresent(JsonNode primary, JsonNode fallback) {
        if (primary != null && !primary.isNull() && !primary.isMissingNode()) {
            return primary;
        }
        return fallback;
    }
}
