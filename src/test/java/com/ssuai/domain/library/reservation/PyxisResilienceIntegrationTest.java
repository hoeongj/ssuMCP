package com.ssuai.domain.library.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.resilience.PyxisResilience;

class PyxisResilienceIntegrationTest {

    private static final String TOKEN = "stub-pyxis-auth-token";
    private static final String BASE_URL = "https://oasis.test.local";
    private static final String RESERVE_URL = BASE_URL + "/pyxis-api/1/api/seat-charges";

    private MockRestServiceServer server;
    private SimpleMeterRegistry meterRegistry;
    private RealLibraryReservationConnector connector;

    @BeforeEach
    void setUp() {
        LibraryReservationProperties properties = new LibraryReservationProperties();
        properties.setBaseUrl(BASE_URL);
        meterRegistry = new SimpleMeterRegistry();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new RealLibraryReservationConnector(
                properties,
                new ObjectMapper(),
                builder.build(),
                PyxisResilience.forTesting(meterRegistry));
    }

    @Test
    void opensCircuitAfterConsecutiveServerErrorsAndShortCircuitsNextCall() {
        server.expect(times(10), requestTo(RESERVE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Pyxis-Auth-Token", TOKEN))
                .andRespond(withServerError());

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                    .isInstanceOfAny(ConnectorUnavailableException.class, ConnectorTimeoutException.class);
        }

        assertThat(meterRegistry.find("resilience4j.circuitbreaker.state")
                .tag("name", "pyxis-write")
                .tag("state", "open")
                .gauge()
                .value()).isEqualTo(1.0);

        assertThatThrownBy(() -> connector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                .isInstanceOf(CallNotPermittedException.class);
        server.verify();
    }
}
