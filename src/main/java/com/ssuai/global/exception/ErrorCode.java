package com.ssuai.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    CONNECTOR_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "?몃? ?쒕퉬???묐떟 ?쒓컙??珥덇낵?섏뿀?듬땲??"),
    CONNECTOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "?몃? ?쒕퉬?ㅻ? ?쇱떆?곸쑝濡??ъ슜?????놁뒿?덈떎."),
    CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "?몃? ?곕룞 ?쒖뒪?쒖씠 ?쇱떆?곸쑝濡?遺덉븞?뺥빐 ?붿껌???좎떆 李⑤떒?덉뒿?덈떎. ?좎떆 ???ㅼ떆 ?쒕룄?댁＜?몄슂."),
    CONNECTOR_PARSE_ERROR(HttpStatus.BAD_GATEWAY, "?몃? ?쒕퉬???묐떟???댁꽍?섏? 紐삵뻽?듬땲??"),
    CONNECTOR_ERROR(HttpStatus.BAD_GATEWAY, "?몃? ?쒕퉬??泥섎━ 以??ㅻ쪟媛 諛쒖깮?덉뒿?덈떎."),
    CHAT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI ?묐떟 湲곕뒫???쇱떆?곸쑝濡??ъ슜?????놁뒿?덈떎."),
    LIBRARY_SESSION_REQUIRED(HttpStatus.UNAUTHORIZED, "?꾩꽌愿 濡쒓렇?몄씠 ?꾩슂?⑸땲??"),
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "?꾩꽌愿 醫뚯꽍???ъ슜?????놁뒿?덈떎."),
    SAINT_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "u-SAINT ?몄뀡??留뚮즺?섏뿀?듬땲?? ?ㅼ떆 濡쒓렇?명빐二쇱꽭??"),
    LMS_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "LMS ?몄쬆???ㅽ뙣?덉뒿?덈떎."),
    LMS_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "LMS ?몄뀡??留뚮즺?섏뿀?듬땲?? ?ㅼ떆 濡쒓렇?명빐二쇱꽭??"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "?몄쬆???꾩슂?⑸땲??"),
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
