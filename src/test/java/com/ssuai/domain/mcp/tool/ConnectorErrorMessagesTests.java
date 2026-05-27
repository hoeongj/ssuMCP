package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

class ConnectorErrorMessagesTests {

    @Test
    void timeoutMessageIncludesResourceLabel() {
        String message = ConnectorErrorMessages.forResource("학식", new ConnectorTimeoutException());

        assertThat(message)
                .contains("학식")
                .contains("지연");
    }

    @Test
    void unavailableMessageIncludesResourceLabel() {
        String message = ConnectorErrorMessages.forResource("학식", new ConnectorUnavailableException());

        assertThat(message)
                .contains("학식")
                .contains("연결할 수 없");
    }

    @Test
    void parseErrorMessageIncludesResourceLabel() {
        String message = ConnectorErrorMessages.forResource("기숙사 식단", new ConnectorParseException());

        assertThat(message)
                .contains("기숙사 식단")
                .contains("응답 구조");
    }

    @Test
    void genericConnectorErrorMessageIncludesResourceLabel() {
        String message = ConnectorErrorMessages.forResource("학식", new ConnectorException());

        assertThat(message)
                .contains("학식")
                .contains("알 수 없는 오류");
    }
}
