package com.ssuai.domain.library.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class RealLibraryLoansConnectorTests {

    private static final String TOKEN = "stub-pyxis-auth-token";
    private static final String BASE_URL = "https://oasis.test.local";
    private static final String LOANS_URL = BASE_URL + "/pyxis-api/1/api/charges?offset=0&max=20";

    private MockRestServiceServer server;
    private RealLibraryLoansConnector connector;

    @BeforeEach
    void setUp() {
        LibrarySeatProperties properties = new LibrarySeatProperties();
        properties.setBaseUrl(BASE_URL);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new RealLibraryLoansConnector(properties, new ObjectMapper(), builder.build());
    }

    @Test
    void parsesFixtureWithTwoLoans() {
        server.expect(requestTo(LOANS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Pyxis-Auth-Token", TOKEN))
                .andRespond(withSuccess(loadFixture("library/loans.json"),
                        MediaType.APPLICATION_JSON));

        LibraryLoansResponse response = connector.fetchLoans(TOKEN);

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.loans()).hasSize(2);
        assertThat(response.loans().get(0).title()).isEqualTo("스프링 부트 핵심 가이드");
        assertThat(response.loans().get(0).isRenewable()).isTrue();
        assertThat(response.loans().get(1).title()).isEqualTo("클린 코드");
        assertThat(response.loans().get(1).isRenewable()).isFalse();
    }

    @Test
    void noRecordCodeReturnsEmptyList() {
        server.expect(requestTo(LOANS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(loadFixture("library/loans-empty.json"),
                        MediaType.APPLICATION_JSON));

        LibraryLoansResponse response = connector.fetchLoans(TOKEN);

        assertThat(response.total()).isZero();
        assertThat(response.loans()).isEmpty();
    }

    @Test
    void throwsLibraryAuthRequiredOnNeedLogin() {
        String needLoginBody = """
                {"success":false,"code":"error.authentication.needLogin","message":"Please log in.","data":null}
                """;
        server.expect(requestTo(LOANS_URL))
                .andRespond(withSuccess(needLoginBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.fetchLoans(TOKEN))
                .isInstanceOf(LibraryAuthRequiredException.class);
    }

    @Test
    void throwsConnectorParseExceptionOnSuccessFalseWithUnknownCode() {
        String body = """
                {"success":false,"code":"error.unknown","message":"Unknown.","data":null}
                """;
        server.expect(requestTo(LOANS_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.fetchLoans(TOKEN))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void throwsConnectorParseExceptionOnEmptyBody() {
        server.expect(requestTo(LOANS_URL))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.fetchLoans(TOKEN))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void serverErrorMapsToConnectorUnavailable() {
        server.expect(requestTo(LOANS_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> connector.fetchLoans(TOKEN))
                .isInstanceOf(ConnectorUnavailableException.class);
    }

    @Test
    void ioExceptionMapsToConnectorTimeout() {
        server.expect(requestTo(LOANS_URL))
                .andRespond(withException(new IOException("connect timed out")));

        assertThatThrownBy(() -> connector.fetchLoans(TOKEN))
                .isInstanceOf(ConnectorTimeoutException.class);
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
