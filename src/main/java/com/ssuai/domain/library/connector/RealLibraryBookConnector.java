package com.ssuai.domain.library.connector;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

import com.ssuai.domain.library.dto.BookStatus;
import com.ssuai.domain.library.dto.LibraryBook;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.monitoring.AlertLevel;
import com.ssuai.global.monitoring.DiscordAlertService;

/**
 * Reads books from the Soongsil University central library via the Pyxis
 * JSON API. Spike-confirmed (2026-05-15) as anonymous GET, no
 * {@code Pyxis-Auth-Token} header needed — the search collection is public
 * even though seat/reservation endpoints are auth-gated.
 *
 * <p>Request shape:
 * {@code GET {baseUrl}/pyxis-api/1/collections/{collectionId}/search?all=k|a|{query}&max=&offset=&isForPyxis3=true}.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.library-book", havingValue = "real")
public class RealLibraryBookConnector implements LibraryBookConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLibraryBookConnector.class);

    private static final String SEARCH_PATH_TEMPLATE = "/pyxis-api/1/collections/%d/search";
    private static final String QUERY_PREFIX = "k|a|";

    private final LibraryBookProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final DiscordAlertService discordAlertService;

    public RealLibraryBookConnector(
            LibraryBookProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("libraryBookRestClient") RestClient restClient,
            DiscordAlertService discordAlertService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.discordAlertService = discordAlertService;
    }

    @Override
    public LibraryBookSearchResponse search(String query, int page, int size) {
        URI uri = buildUri(query, page, size);
        String body;
        randomDelay();
        try {
            body = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Accept-Language", "ko")
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException exception) {
            log.warn("library book connector timeout/io: queryLen={} page={} size={}",
                    query == null ? 0 : query.length(), page, size);
            throw alert(new ConnectorTimeoutException(exception));
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            log.warn("library book connector http error: status={} queryLen={}",
                    status.value(), query == null ? 0 : query.length());
            if (status.is5xxServerError()) {
                throw alert(new ConnectorUnavailableException(exception));
            }
            throw new ConnectorParseException(exception);
        }

        return parseBody(body, page, size);
    }

    private LibraryBookSearchResponse parseBody(String body, int page, int size) {
        if (body == null || body.isBlank()) {
            throw new ConnectorParseException();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new ConnectorParseException(exception);
        }

        if (!root.path("success").asBoolean(false)) {
            String code = root.path("code").asText("");
            log.warn("library book search returned success=false: code={}", code);
            // Anonymous search is supposed to succeed. A `needLogin` here means
            // upstream changed shape (e.g. moved the catalog behind auth) — fail
            // loudly so we notice instead of returning empty results.
            throw new ConnectorParseException();
        }

        JsonNode data = root.path("data");
        int total = data.path("totalCount").asInt(0);
        JsonNode list = data.path("list");

        List<LibraryBook> items = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode entry : list) {
                items.add(toBook(entry));
            }
        }
        return new LibraryBookSearchResponse(total, page, size, items);
    }

    private LibraryBook toBook(JsonNode entry) {
        long id = entry.path("id").asLong(0L);
        String title = textOrFallback(entry.path("titleStatement"), "(제목 미상)");
        String author = textOrNull(entry.path("author"));
        String publication = textOrNull(entry.path("publication"));
        String isbn = textOrNull(entry.path("isbn"));
        String thumbnailUrl = textOrNull(entry.path("thumbnailUrl"));

        JsonNode firstVolume = entry.path("branchVolumes").path(0);
        String callNumber = textOrNull(firstVolume.path("volume"));
        String location = textOrNull(firstVolume.path("name"));
        BookStatus status = BookStatus.fromPyxisCode(textOrNull(firstVolume.path("cStateCode")));

        return new LibraryBook(id, title, author, publication, isbn,
                thumbnailUrl, callNumber, location, status);
    }

    private URI buildUri(String query, int page, int size) {
        String encoded = URLEncoder.encode(QUERY_PREFIX + (query == null ? "" : query),
                StandardCharsets.UTF_8);
        int offset = page * size;
        String path = SEARCH_PATH_TEMPLATE.formatted(properties.getCollectionId());
        String full = "%s?all=%s&facet=false&fuzzy=false&max=%d&offset=%d&isForPyxis3=true"
                .formatted(path, encoded, size, offset);
        return URI.create(full);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    private static String textOrFallback(JsonNode node, String fallback) {
        String value = textOrNull(node);
        return value == null ? fallback : value;
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

    private static void randomDelay() {
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 1200));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
