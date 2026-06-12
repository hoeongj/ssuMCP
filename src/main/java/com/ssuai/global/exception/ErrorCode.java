package com.ssuai.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    CONNECTOR_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "?ëª? ??•í‰¬???ë¬ë–Ÿ ??“ì»™???¥ë‡???ë???¬ë•²??"),
    CONNECTOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "?ëª? ??•í‰¬??? ??±ë–†?ê³¸ì‘æ¿??????????ë’¿??ˆë–."),
    CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "?ëª? ?ê³•ë£ ??–ë’ª??–ì”  ??±ë–†?ê³¸ì‘æ¿??ºë‰ë¸?ëº¥ë¹ ?ë¶¿ê»Œ???ì¢ë–† ï§¡â‘¤???‰ë’¿??ˆë–. ?ì¢ë–† ????¼ë–† ??•ë£„??ï¼œ?ëª„ìŠ‚."),
    CONNECTOR_PARSE_ERROR(HttpStatus.BAD_GATEWAY, "?ëª? ??•í‰¬???ë¬ë–Ÿ????ê½??? ï§ì‚µë»??¬ë•²??"),
    CONNECTOR_ERROR(HttpStatus.BAD_GATEWAY, "?ëª? ??•í‰¬??ï§£ì„??ä»???»ìªŸåª›Â€ è«›ì’–ê¹??‰ë’¿??ˆë–."),
    CHAT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI ?ë¬ë–Ÿ æ¹²ê³•?????±ë–†?ê³¸ì‘æ¿??????????ë’¿??ˆë–."),
    LIBRARY_SESSION_REQUIRED(HttpStatus.UNAUTHORIZED, "?ê¾©ê½Œ?¿Â€ æ¿¡ì’“??ëª„ì”  ?ê¾©ìŠ‚??¸ë•²??"),
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "?ê¾©ê½Œ?¿Â€ ?«ëš¯ê½???????????ë’¿??ˆë–."),
    ACTIVE_WAIT_EXISTS(HttpStatus.CONFLICT, "È°¼ºÈ­µÈ ÁÂ¼® ´ë±â ½ÅÃ»ÀÌ ÀÌ¹Ì Á¸ÀçÇÕ´Ï´Ù."),
    SAINT_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "u-SAINT ?ëª„ë€??ï§ëš®ì¦??ë???¬ë•²?? ??¼ë–† æ¿¡ì’“??ëª…ë¹äºŒì‡±ê½??"),
    LMS_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "LMS ?ëª„ì¬†????½ë™£??‰ë’¿??ˆë–."),
    LMS_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "LMS ?ëª„ë€??ï§ëš®ì¦??ë???¬ë•²?? ??¼ë–† æ¿¡ì’“??ëª…ë¹äºŒì‡±ê½??"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "?ëª„ì¬†???ê¾©ìŠ‚??¸ë•²??"),
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
