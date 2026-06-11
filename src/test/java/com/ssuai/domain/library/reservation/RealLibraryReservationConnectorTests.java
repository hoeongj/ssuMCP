package com.ssuai.domain.library.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;
import com.ssuai.global.resilience.PyxisResilience;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RealLibraryReservationConnectorTests {

    private static final String TOKEN = "stub-pyxis-auth-token";
    private static final String BASE_URL = "https://oasis.test.local";
    private static final String RESERVE_URL = BASE_URL + "/pyxis-api/1/api/seat-charges";
    private static final String DISCHARGE_URL = BASE_URL + "/pyxis-api/1/api/seat-discharges";

    private MockRestServiceServer server;
    private RealLibraryReservationConnector connector;

    @BeforeEach
    void setUp() {
        LibraryReservationProperties properties = new LibraryReservationProperties();
        properties.setBaseUrl(BASE_URL);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new RealLibraryReservationConnector(
                properties, new ObjectMapper(), builder.build(),
                new PyxisResilience(new SimpleMeterRegistry()));
    }

    @Test
    void reserveReturnsSeatCharge() {
        server.expect(requestTo(RESERVE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Pyxis-Auth-Token", TOKEN))
                .andExpect(content().json("""
                        {"seatId": 3179, "smufMethodCode": "PC"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "code": "success.processed",
                          "data": {
                            "id": 1966693,
                            "room": {"id": 57, "name": "마루열람실(6F)"},
                            "seat": {"id": 3179, "code": "74"},
                            "beginTime": "2026-06-06 14:59:00",
                            "endTime": "2026-06-06 18:59:00"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        LibraryReservationResult result = connector.reserve(TOKEN, new LibraryReservationRequest(3179L));

        assertThat(result.chargeId()).isEqualTo(1966693L);
        assertThat(result.roomName()).isEqualTo("마루열람실(6F)");
        assertThat(result.seatCode()).isEqualTo("74");
        assertThat(result.beginTime()).isEqualTo("2026-06-06 14:59:00");
        assertThat(result.endTime()).isEqualTo("2026-06-06 18:59:00");
    }

    @Test
    void dischargeSucceeds() {
        server.expect(requestTo(DISCHARGE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Pyxis-Auth-Token", TOKEN))
                .andExpect(content().json("""
                        {"seatCharge": 1966693, "smufMethodCode": "PC"}
                        """))
                .andRespond(withSuccess("""
                        {"success": true, "code": "success.discharge", "message": "반납되었습니다."}
                        """, MediaType.APPLICATION_JSON));

        assertThatNoException()
                .isThrownBy(() -> connector.discharge(TOKEN, 1966693L));
    }

    @Test
    void reserveThrowsLibraryAuthRequiredOnNeedLogin() {
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withSuccess("""
                        {"success":false,"code":"error.authentication.needLogin","message":"Please log in.","data":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                .isInstanceOf(LibraryAuthRequiredException.class);
    }

    @Test
    void reserveThrowsConnectorParseExceptionOnSuccessFalse() {
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withSuccess("""
                        {"success":false,"code":"error.unknown","message":"Unknown.","data":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void reserveServerErrorMapsToConnectorUnavailable() {
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                .isInstanceOf(ConnectorUnavailableException.class);
    }

    @Test
    void reserveIoExceptionMapsToConnectorTimeout() {
        server.expect(requestTo(RESERVE_URL))
                .andRespond(withException(new IOException("connect timed out")));

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                .isInstanceOf(ConnectorTimeoutException.class);
    }

    @Test
    void dischargeThrowsLibraryAuthRequiredOnNeedLogin() {
        server.expect(requestTo(DISCHARGE_URL))
                .andRespond(withSuccess("""
                        {"success":false,"code":"error.authentication.needLogin","message":"Please log in.","data":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.discharge(TOKEN, 1966693L))
                .isInstanceOf(LibraryAuthRequiredException.class);
    }

    @Test
    void dischargeThrowsSeatNotAvailableOnNotAvailableState() {
        server.expect(requestTo(DISCHARGE_URL))
                .andRespond(withSuccess("""
                        {"success":false,"code":"warning.smuf.notAvailableState","message":"현재 상태에서는 처리할 수 없습니다.","data":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.discharge(TOKEN, 1966693L))
                .isInstanceOfSatisfying(LibrarySeatNotAvailableException.class, exception ->
                        assertThat(exception.getPyxisCode()).isEqualTo("warning.smuf.notAvailableState"));
    }

    @Test
    void dischargeServerErrorMapsToConnectorUnavailable() {
        server.expect(requestTo(DISCHARGE_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> connector.discharge(TOKEN, 1966693L))
                .isInstanceOf(ConnectorUnavailableException.class);
    }
}
