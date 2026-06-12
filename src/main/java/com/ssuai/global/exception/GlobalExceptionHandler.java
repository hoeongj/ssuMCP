package com.ssuai.global.exception;

import java.util.Optional;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ssuai.global.response.ApiResponse;
import com.ssuai.global.response.ErrorResponse;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public GlobalExceptionHandler() {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(this::formatFieldError)
                .orElse(ErrorCode.VALIDATION_FAILED.getDefaultMessage());

        return validationFailed(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception
    ) {
        String message = exception.getParameterValidationResults()
                .stream()
                .flatMap(result -> result.getResolvableErrors()
                        .stream()
                        .map(error -> formatValidationError(result.getMethodParameter().getParameterName(), error)))
                .findFirst()
                .orElse(ErrorCode.VALIDATION_FAILED.getDefaultMessage());

        return validationFailed(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        String message = exception.getConstraintViolations()
                .stream()
                .findFirst()
                .map(this::formatConstraintViolation)
                .orElse(ErrorCode.VALIDATION_FAILED.getDefaultMessage());

        return validationFailed(message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception
    ) {
        return validationFailed(exception.getParameterName() + ": required request parameter is missing");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleNoResourceFoundException(
            NoResourceFoundException exception
    ) {
        log.debug("No resource found: resourcePath={}", exception.getResourcePath());

        ErrorCode errorCode = ErrorCode.NOT_FOUND;
        ErrorResponse errorResponse = new ErrorResponse(
                errorCode.name(),
                "No resource for " + exception.getResourcePath()
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorResponse));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException exception
    ) {
        ErrorCode errorCode = ErrorCode.METHOD_NOT_ALLOWED;
        ErrorResponse errorResponse = new ErrorResponse(errorCode.name(), exception.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorResponse));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleApiException(ApiException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        ErrorResponse errorResponse = new ErrorResponse(errorCode.name(), exception.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorResponse));
    }

    @ExceptionHandler(ConnectorTimeoutException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleConnectorTimeoutException(
            ConnectorTimeoutException exception
    ) {
        log.warn("Connector exception: code={} type={}",
                ErrorCode.CONNECTOR_TIMEOUT.name(), exception.getClass().getSimpleName(), exception);

        return error(ErrorCode.CONNECTOR_TIMEOUT);
    }

    @ExceptionHandler(ConnectorUnavailableException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleConnectorUnavailableException(
            ConnectorUnavailableException exception
    ) {
        log.warn("Connector exception: code={} type={}",
                ErrorCode.CONNECTOR_UNAVAILABLE.name(), exception.getClass().getSimpleName(), exception);

        return error(ErrorCode.CONNECTOR_UNAVAILABLE);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleCallNotPermittedException(
            CallNotPermittedException exception
    ) {
        log.warn("Circuit breaker open: name={}", exception.getCausingCircuitBreakerName());
        return error(ErrorCode.CIRCUIT_OPEN);
    }

    @ExceptionHandler(ConnectorParseException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleConnectorParseException(
            ConnectorParseException exception
    ) {
        log.warn("Connector exception: code={} type={}",
                ErrorCode.CONNECTOR_PARSE_ERROR.name(), exception.getClass().getSimpleName(), exception);

        return error(ErrorCode.CONNECTOR_PARSE_ERROR);
    }

    @ExceptionHandler(ConnectorException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleConnectorException(ConnectorException exception) {
        log.warn("Connector exception: code={} type={}",
                ErrorCode.CONNECTOR_ERROR.name(), exception.getClass().getSimpleName(), exception);

        return error(ErrorCode.CONNECTOR_ERROR);
    }

    @ExceptionHandler(ChatUnavailableException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleChatUnavailableException(
            ChatUnavailableException exception
    ) {
        log.warn("Chat exception: code={} type={}",
                ErrorCode.CHAT_UNAVAILABLE.name(), exception.getClass().getSimpleName(), exception);

        return error(ErrorCode.CHAT_UNAVAILABLE);
    }

    @ExceptionHandler(LibrarySeatNotAvailableException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleLibrarySeatNotAvailableException(
            LibrarySeatNotAvailableException exception
    ) {
        log.warn("Library seat exception: code={} pyxisCode={}",
                ErrorCode.SEAT_NOT_AVAILABLE.name(), exception.getPyxisCode());

        return error(ErrorCode.SEAT_NOT_AVAILABLE);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleIllegalArgumentException(
            IllegalArgumentException exception
    ) {
        return validationFailed(exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> handleException(Exception exception) {
        log.error("Unhandled exception occurred: exceptionType={}", exception.getClass().getName(), exception);

        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        ErrorResponse errorResponse = new ErrorResponse(errorCode.name(), "Internal server error");

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorResponse));
    }

    private ResponseEntity<ApiResponse<ErrorResponse>> error(ErrorCode errorCode) {
        ErrorResponse errorResponse = new ErrorResponse(errorCode.name(), errorCode.getDefaultMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorResponse));
    }

    private ResponseEntity<ApiResponse<ErrorResponse>> validationFailed(String message) {
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        ErrorResponse errorResponse = new ErrorResponse(errorCode.name(), message);

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorResponse));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private String formatValidationError(String parameterName, MessageSourceResolvable error) {
        return safeName(parameterName) + ": " + error.getDefaultMessage();
    }

    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        return Optional.ofNullable(violation.getPropertyPath())
                .map(Object::toString)
                .map(path -> path.substring(path.lastIndexOf('.') + 1))
                .map(this::safeName)
                .map(name -> name + ": " + violation.getMessage())
                .orElse(violation.getMessage());
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "request";
        }
        return name;
    }
}
