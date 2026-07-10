package com.ssuai.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    CONNECTOR_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "외부 서비스 응답 시간이 초과되었습니다."),
    CONNECTOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 서비스를 일시적으로 사용할 수 없습니다."),
    UPSTREAM_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "외부 서비스 요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),
    CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "외부 연동 서비스가 일시적으로 차단되어 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."),
    CONNECTOR_PARSE_ERROR(HttpStatus.BAD_GATEWAY, "외부 서비스 응답을 해석하지 못했습니다."),
    CONNECTOR_ERROR(HttpStatus.BAD_GATEWAY, "외부 서비스 처리 중 오류가 발생했습니다."),
    CHAT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 응답 기능을 일시적으로 사용할 수 없습니다."),
    LIBRARY_SESSION_REQUIRED(HttpStatus.UNAUTHORIZED, "도서관 로그인이 필요합니다."),
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "해당 좌석은 현재 이용할 수 없습니다."),
    ACTIVE_WAIT_EXISTS(HttpStatus.CONFLICT, "활성화된 좌석 대기 요청이 이미 존재합니다."),
    SAINT_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "u-SAINT 세션이 만료되었습니다. 다시 로그인해주세요."),
    LMS_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "LMS 인증에 실패했습니다."),
    LMS_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "LMS 세션이 만료되었습니다. 다시 로그인해주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
