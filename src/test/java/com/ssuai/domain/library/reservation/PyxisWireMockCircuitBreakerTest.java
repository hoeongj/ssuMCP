package com.ssuai.domain.library.reservation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.resilience.PyxisResilience;

/**
 * WireMock-based integration test for Pyxis circuit breaker.
 *
 * <p>Uses a real HTTP stub server to verify that consecutive 5xx responses from the
 * Pyxis reservation endpoint cause the circuit breaker to open and short-circuit
 * subsequent calls without hitting the upstream.
 */
class PyxisWireMockCircuitBreakerTest {

    private static final String RESERVE_PATH = "/pyxis-api/1/api/seat-charges";
    private static final String STUB_TOKEN = "stub-pyxis-auth-token";

    private WireMockServer wireMockServer;
    private RealLibraryReservationConnector connector;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());

        String baseUrl = "http://localhost:" + wireMockServer.port();
        LibraryReservationProperties properties = new LibraryReservationProperties();
        properties.setBaseUrl(baseUrl);

        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        PyxisResilience pyxisResilience = PyxisResilience.forTesting(new SimpleMeterRegistry());

        connector = new RealLibraryReservationConnector(
                properties,
                new ObjectMapper(),
                restClient,
                pyxisResilience);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void circuitOpensAfterConsecutive5xxFromPyxisAndShortCircuitsNextCall() {
        // Stub all POST requests to /pyxis-api/1/api/seat-charges with 500
        stubFor(post(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse().withStatus(500)));

        // Drive calls until CallNotPermittedException (circuit open) or exhaust budget
        boolean circuitOpened = false;
        int maxCalls = 20;
        int callsBeforeOpen = 0;

        for (int i = 0; i < maxCalls; i++) {
            try {
                connector.reserve(STUB_TOKEN, new LibraryReservationRequest(3179L));
            } catch (CallNotPermittedException e) {
                circuitOpened = true;
                callsBeforeOpen = i;
                break;
            } catch (ConnectorUnavailableException | ConnectorTimeoutException ignored) {
                // expected transient failures while circuit is still CLOSED
            }
        }

        assertThat(circuitOpened)
                .as("Circuit breaker should have opened after repeated 5xx responses")
                .isTrue();

        // Record WireMock request count at the moment circuit opened
        int requestsBeforeOpen = wireMockServer.getAllServeEvents().size();

        // Next call must also be short-circuited (no new HTTP request to WireMock)
        assertThatThrownBy(() -> connector.reserve(STUB_TOKEN, new LibraryReservationRequest(3179L)))
                .isInstanceOf(CallNotPermittedException.class);

        // WireMock should have received no additional requests
        verify(requestsBeforeOpen, postRequestedFor(urlEqualTo(RESERVE_PATH)));
        assertThat(wireMockServer.getAllServeEvents().size())
                .as("No new HTTP request should have been made after circuit opened")
                .isEqualTo(requestsBeforeOpen);
    }
}
