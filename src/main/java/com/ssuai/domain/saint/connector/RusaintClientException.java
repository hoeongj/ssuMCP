package com.ssuai.domain.saint.connector;

public class RusaintClientException extends RuntimeException {

    public RusaintClientException(String message) {
        super(message);
    }

    public RusaintClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
