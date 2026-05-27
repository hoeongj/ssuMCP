package com.ssuai.domain.mcp.tool;

import com.ssuai.global.exception.ConnectorException;

final class ConnectorErrorMessages {

    private ConnectorErrorMessages() {
    }

    static String forResource(String resourceLabel, ConnectorException exception) {
        return switch (exception.getErrorCode()) {
            case CONNECTOR_TIMEOUT -> "외부 사이트 응답이 지연되어 " + resourceLabel
                    + " 정보를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
            case CONNECTOR_UNAVAILABLE -> "외부 사이트에 연결할 수 없어 " + resourceLabel
                    + " 정보를 가져오지 못했습니다.";
            case CONNECTOR_PARSE_ERROR -> "외부 사이트의 응답 구조가 변경되어 " + resourceLabel
                    + " 정보를 분석하지 못했습니다.";
            default -> "외부 사이트 호출 중 알 수 없는 오류로 " + resourceLabel
                    + " 정보를 가져오지 못했습니다.";
        };
    }
}
