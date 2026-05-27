package com.ssuai.global.exception;

public class ConnectorParseException extends ConnectorException {

    public ConnectorParseException() {
        super(ErrorCode.CONNECTOR_PARSE_ERROR);
    }

    public ConnectorParseException(Throwable cause) {
        super(ErrorCode.CONNECTOR_PARSE_ERROR, cause);
    }
}
