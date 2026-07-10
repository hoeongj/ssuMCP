package com.ssuai.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ssuai.global.resilience.PyxisResilience;
import com.ssuai.global.response.ApiResponse;
import com.ssuai.global.response.ErrorResponse;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void connectorUnavailableReturns503() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleConnectorUnavailableException(new ConnectorUnavailableException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void callNotPermittedReturnsCircuitOpen503() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("pyxis");
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleCallNotPermittedException(
                        CallNotPermittedException.createCallNotPermittedException(circuitBreaker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.CIRCUIT_OPEN.name());
    }

    @Test
    void connectorParseReturns502() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleConnectorParseException(new ConnectorParseException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void connectorRateLimitedReturns429WithRetryAfter() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleConnectorRateLimitedException(
                        new ConnectorRateLimitedException(Duration.ofSeconds(2), new RuntimeException("429")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.UPSTREAM_RATE_LIMITED.name());
    }

    @Test
    void connectorTimeoutReturns504() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleConnectorTimeoutException(new ConnectorTimeoutException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void asyncRequestTimeoutReturns503AndIsNotTreatedAsUnhandledError() {
        // A dedicated handler keeps idle SSE/long-poll timeouts off the ERROR-level
        // Exception catch-all (which would log a full stack trace per stream close).
        ResponseEntity<Void> response = handler.handleAsyncRequestTimeout();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void seatNotAvailableReturns409() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleLibrarySeatNotAvailableException(
                        new LibrarySeatNotAvailableException("warning.smuf.notAvailableState"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.SEAT_NOT_AVAILABLE.name());
    }

    @Test
    void illegalArgumentReturns400() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleIllegalArgumentException(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void callNotPermittedInHalfOpenStateReturnsRecoveryMessage() {
        PyxisResilience mockResilience = mock(PyxisResilience.class);
        when(mockResilience.circuitBreakerState()).thenReturn(CircuitBreaker.State.HALF_OPEN);

        GlobalExceptionHandler handlerWithResilience = new GlobalExceptionHandler();
        handlerWithResilience.setPyxisResilience(mockResilience);

        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("pyxis");
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handlerWithResilience.handleCallNotPermittedException(
                        CallNotPermittedException.createCallNotPermittedException(circuitBreaker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.CIRCUIT_OPEN.name());
        assertThat(response.getBody().error().message()).contains("복구 중");
    }
}
