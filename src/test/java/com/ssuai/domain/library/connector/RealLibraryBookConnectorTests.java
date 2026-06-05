package com.ssuai.domain.library.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssuai.domain.library.dto.BookStatus;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

class RealLibraryBookConnectorTests {

    private LibraryBookProperties properties;
    private MockRestServiceServer server;
    private RealLibraryBookConnector connector;

    @BeforeEach
    void setUp() {
        properties = new LibraryBookProperties();
        properties.setBaseUrl("https://oasis.test.local");
        properties.setCollectionId(2);
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        connector = new RealLibraryBookConnector(properties, new ObjectMapper(), restClient);
    }

    @Test
    void parsesPythonSearchFixtureIntoDomainBooks() {
        server.expect(requestTo(expectedUri("파이썬", 0, 4)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(loadFixture("library/book-search-python.json"),
                        MediaType.APPLICATION_JSON));

        LibraryBookSearchResponse response = connector.search("파이썬", 0, 4);

        assertThat(response.total()).isEqualTo(755);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(4);
        assertThat(response.items()).hasSize(4);

        assertThat(response.items().get(0).id()).isEqualTo(5006619L);
        assertThat(response.items().get(0).title()).isEqualTo("파이썬 : 기초와 활용");
        assertThat(response.items().get(0).author()).isEqualTo("한정란");
        assertThat(response.items().get(0).callNumber()).isEqualTo("005.133P9 한7362파");
        assertThat(response.items().get(0).location()).isEqualTo("중앙도서관");
        assertThat(response.items().get(0).status()).isEqualTo(BookStatus.AVAILABLE);
        assertThat(response.items().get(0).isbn()).isEqualTo("9791168330702");
        assertThat(response.items().get(0).thumbnailUrl()).startsWith("https://image.aladin.co.kr/");
    }

    @Test
    void loanedBookMapsToCheckedOut() {
        server.expect(requestTo(expectedUri("파이썬", 0, 4)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(loadFixture("library/book-search-python.json"),
                        MediaType.APPLICATION_JSON));

        LibraryBookSearchResponse response = connector.search("파이썬", 0, 4);

        // (새내기) 파이썬 — cStateCode = LOAN
        assertThat(response.items().get(2).status()).isEqualTo(BookStatus.CHECKED_OUT);
    }

    @Test
    void emptyResultsAreParsedAsZeroTotal() {
        server.expect(requestTo(expectedUri("xyzzy", 0, 10)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(loadFixture("library/book-search-empty.json"),
                        MediaType.APPLICATION_JSON));

        LibraryBookSearchResponse response = connector.search("xyzzy", 0, 10);

        assertThat(response.total()).isZero();
        assertThat(response.items()).isEmpty();
    }

    @Test
    void needLoginResponseThrowsParseException() {
        server.expect(requestTo(expectedUri("파이썬", 0, 10)))
                .andRespond(withSuccess(loadFixture("library/book-search-needlogin.json"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.search("파이썬", 0, 10))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void serverErrorMapsToConnectorUnavailable() {
        server.expect(requestTo(expectedUri("파이썬", 0, 10)))
                .andRespond(withServerError());

        assertThatThrownBy(() -> connector.search("파이썬", 0, 10))
                .isInstanceOf(ConnectorUnavailableException.class);
    }

    @Test
    void clientErrorMapsToConnectorParse() {
        server.expect(requestTo(expectedUri("파이썬", 0, 10)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> connector.search("파이썬", 0, 10))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void ioExceptionMapsToConnectorTimeout() {
        server.expect(requestTo(expectedUri("파이썬", 0, 10)))
                .andRespond(withException(new IOException("connect timed out")));

        assertThatThrownBy(() -> connector.search("파이썬", 0, 10))
                .isInstanceOf(ConnectorTimeoutException.class);
    }

    @Test
    void uriEncodesKoreanQueryAndPaginationOffset() {
        // page=2, size=10 → offset=20
        server.expect(requestTo(expectedUri("자바", 2, 10)))
                .andRespond(withSuccess(loadFixture("library/book-search-empty.json"),
                        MediaType.APPLICATION_JSON));

        connector.search("자바", 2, 10);

        server.verify();
    }

    private String expectedUri(String query, int page, int size) {
        String encoded = java.net.URLEncoder.encode("k|a|" + query, StandardCharsets.UTF_8);
        return "https://oasis.test.local/pyxis-api/1/collections/2/search"
                + "?all=" + encoded
                + "&facet=false&fuzzy=false"
                + "&max=" + size
                + "&offset=" + (page * size)
                + "&isForPyxis3=true";
    }

    private String loadFixture(String classpath) {
        try {
            return new String(new ClassPathResource(classpath).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
