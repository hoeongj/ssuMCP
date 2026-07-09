package com.ssuai.domain.library.reservation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
    private static final String NO_RECORD_BODY =
            "{\"success\":true,\"code\":\"success.noRecord\",\"message\":\"no record\",\"data\":null}";
    private static final String RESERVE_SUCCESS_BODY = """
            {
              "success": true,
              "code": "success.charged",
              "data": {
                "id": 1968552,
                "room": { "id": 58, "name": "room" },
                "seat": { "id": 3179, "code": "3179" },
                "beginTime": "2026-07-10 09:00",
                "endTime": "2026-07-10 13:00"
              }
            }
            """;

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

        stubFor(get(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(NO_RECORD_BODY)));

        assertThat(connector.getCurrentCharge(STUB_TOKEN)).isEmpty();
        verify(1, getRequestedFor(urlEqualTo(RESERVE_PATH)));
    }

    @Test
    void readCircuitOpenDoesNotBlockReservationWrite() {
        stubFor(get(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse().withStatus(500)));
        stubFor(post(urlEqualTo(RESERVE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RESERVE_SUCCESS_BODY)));

        boolean readCircuitOpened = false;
        int previousGetRequests = 0;
        int maxCalls = 10;
        for (int i = 0; i < maxCalls; i++) {
            connector.getCurrentCharge(STUB_TOKEN);
            int getRequests = wireMockServer.findAll(getRequestedFor(urlEqualTo(RESERVE_PATH))).size();
            if (getRequests == previousGetRequests) {
                readCircuitOpened = true;
                break;
            }
            previousGetRequests = getRequests;
        }

        assertThat(readCircuitOpened)
                .as("Read circuit breaker should eventually short-circuit GET calls")
                .isTrue();

        LibraryReservationResult result = connector.reserve(STUB_TOKEN, new LibraryReservationRequest(3179L));

        assertThat(result.seatId()).isEqualTo(3179L);
        verify(1, postRequestedFor(urlEqualTo(RESERVE_PATH)));
    }
}
