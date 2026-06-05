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

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class RealLibrarySeatConnectorTests {

    private static final String TOKEN = "stub-pyxis-auth-token";
    private static final String BASE_URL = "https://oasis.test.local";
    private static final String SEAT_ROOMS_URL =
            BASE_URL + "/pyxis-api/1/seat-rooms?smufMethodCode=PC&branchGroupId=1";

    private MockRestServiceServer server;
    private RealLibrarySeatConnector connector;

    @BeforeEach
    void setUp() {
        LibrarySeatProperties properties = new LibrarySeatProperties();
        properties.setBaseUrl(BASE_URL);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new RealLibrarySeatConnector(properties, new ObjectMapper(), builder.build());
    }

    @Test
    void parsesFixtureForFloor2() {
        // 2층에는 숭실스퀘어ON(total=112, avail=110) + 오픈열람실(total=232, avail=231) 두 열람실
        server.expect(requestTo(SEAT_ROOMS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Pyxis-Auth-Token", TOKEN))
                .andRespond(withSuccess(loadFixture("library/seat-rooms.json"),
                        MediaType.APPLICATION_JSON));

        LibrarySeatStatusResponse response = connector.fetchSeatStatus(LibraryFloor.F2, TOKEN);

        assertThat(response.floor()).isEqualTo(2);
        assertThat(response.totalSeats()).isEqualTo(344);       // 112 + 232
        assertThat(response.availableSeats()).isEqualTo(341);   // 110 + 231
        assertThat(response.reservedSeats()).isEqualTo(3);      // 2 + 1 (occupied)
        assertThat(response.outOfServiceSeats()).isEqualTo(10); // 10 + 0
        assertThat(response.zones()).hasSize(2);
        assertThat(response.zones().get(0).label()).isEqualTo("숭실스퀘어ON(2F)");
        assertThat(response.zones()).allSatisfy(zone -> {
            assertThat(zone.seatIds()).isEmpty();
            assertThat(zone.seats()).isEmpty();
        });
        assertThat(response.fetchedAt()).isNotNull();
    }

    @Test
    void parsesFixtureForFloor5() {
        // 5층: 숭실멀티라운지(total=98) + 리클라이너(total=6)
        server.expect(requestTo(SEAT_ROOMS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(loadFixture("library/seat-rooms.json"),
                        MediaType.APPLICATION_JSON));

        LibrarySeatStatusResponse response = connector.fetchSeatStatus(LibraryFloor.F5, TOKEN);

        assertThat(response.floor()).isEqualTo(5);
        assertThat(response.totalSeats()).isEqualTo(104);   // 98 + 6
        assertThat(response.availableSeats()).isEqualTo(104); // 98 + 6
        assertThat(response.zones()).hasSize(2);
    }

    @Test
    void throwsLibraryAuthRequiredOnNeedLogin() {
        String needLoginBody = """
                {"success":false,"code":"error.authentication.needLogin","message":"Please log in.","data":null}
                """;
        server.expect(requestTo(SEAT_ROOMS_URL))
                .andRespond(withSuccess(needLoginBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.fetchSeatStatus(LibraryFloor.F2, TOKEN))
                .isInstanceOf(LibraryAuthRequiredException.class);
    }

    @Test
    void throwsConnectorParseExceptionOnSuccessFalseWithUnknownCode() {
        String body = """
                {"success":false,"code":"error.unknown","message":"Unknown.","data":null}
                """;
        server.expect(requestTo(SEAT_ROOMS_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.fetchSeatStatus(LibraryFloor.F2, TOKEN))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void throwsConnectorParseExceptionOnEmptyBody() {
        server.expect(requestTo(SEAT_ROOMS_URL))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.fetchSeatStatus(LibraryFloor.F2, TOKEN))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void serverErrorMapsToConnectorUnavailable() {
        server.expect(requestTo(SEAT_ROOMS_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> connector.fetchSeatStatus(LibraryFloor.F2, TOKEN))
                .isInstanceOf(ConnectorUnavailableException.class);
    }

    @Test
    void ioExceptionMapsToConnectorTimeout() {
        server.expect(requestTo(SEAT_ROOMS_URL))
                .andRespond(withException(new IOException("connect timed out")));

        assertThatThrownBy(() -> connector.fetchSeatStatus(LibraryFloor.F2, TOKEN))
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
