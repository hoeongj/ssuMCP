package com.ssuai.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ssuai.global.monitoring.AlertLevel;
import com.ssuai.global.monitoring.DiscordAlertService;
import com.ssuai.global.response.ApiResponse;
import com.ssuai.global.response.ErrorResponse;

class GlobalExceptionHandlerTests {

    @Test
    void connectorUnavailableSendsErrorAlert() {
        DiscordAlertService alertService = org.mockito.Mockito.mock(DiscordAlertService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(alertService);
        ConnectorUnavailableException exception = new ConnectorUnavailableException();

        ResponseEntity<ApiResponse<ErrorResponse>> response = handler.handleConnectorUnavailableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(alertService).alertConnectorFailure(AlertLevel.ERROR, ErrorCode.CONNECTOR_UNAVAILABLE, exception);
    }

    @Test
    void connectorParseSendsWarningAlert() {
        DiscordAlertService alertService = org.mockito.Mockito.mock(DiscordAlertService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(alertService);
        ConnectorParseException exception = new ConnectorParseException();

        ResponseEntity<ApiResponse<ErrorResponse>> response = handler.handleConnectorParseException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(alertService).alertConnectorFailure(AlertLevel.WARNING, ErrorCode.CONNECTOR_PARSE_ERROR, exception);
    }

    @Test
    void alertFailureDoesNotFailApiResponse() {
        DiscordAlertService alertService = org.mockito.Mockito.mock(DiscordAlertService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(alertService);
        ConnectorUnavailableException exception = new ConnectorUnavailableException();
        doThrow(new RuntimeException("webhook down"))
                .when(alertService)
                .alertConnectorFailure(AlertLevel.ERROR, ErrorCode.CONNECTOR_UNAVAILABLE, exception);

        assertThatCode(() -> handler.handleConnectorUnavailableException(exception))
                .doesNotThrowAnyException();
    }

    @Test
    void normalUserErrorsDoNotAlert() {
        DiscordAlertService alertService = org.mockito.Mockito.mock(DiscordAlertService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(alertService);

        handler.handleIllegalArgumentException(new IllegalArgumentException("bad request"));
        handler.handleApiException(new UnauthorizedException());

        verifyNoInteractions(alertService);
    }
}
