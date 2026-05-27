package com.ssuai.global.exception;

public class ConnectorUnavailableException extends ConnectorException {

    public ConnectorUnavailableException() {
        super(ErrorCode.CONNECTOR_UNAVAILABLE);
    }

    public ConnectorUnavailableException(Throwable cause) {
        super(ErrorCode.CONNECTOR_UNAVAILABLE, cause);
    }
}
