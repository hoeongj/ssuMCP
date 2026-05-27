package com.ssuai.global.exception;

public class ChatUnavailableException extends ApiException {

    public ChatUnavailableException() {
        super(ErrorCode.CHAT_UNAVAILABLE);
    }

    public ChatUnavailableException(Throwable cause) {
        super(ErrorCode.CHAT_UNAVAILABLE);
        initCause(cause);
    }
}
