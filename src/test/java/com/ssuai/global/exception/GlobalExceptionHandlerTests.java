package com.ssuai.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
    void connectorTimeoutReturns504() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleConnectorTimeoutException(new ConnectorTimeoutException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void illegalArgumentReturns400() {
        ResponseEntity<ApiResponse<ErrorResponse>> response =
                handler.handleIllegalArgumentException(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
