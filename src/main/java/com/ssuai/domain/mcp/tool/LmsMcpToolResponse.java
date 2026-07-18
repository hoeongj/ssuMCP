package com.ssuai.domain.mcp.tool;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.global.exception.LmsApiException;

/** Consistent, privacy-safe mapping for non-authentication LMS failures. */
final class LmsMcpToolResponse {

    private LmsMcpToolResponse() {
    }

    static <T> McpPrivateToolResponse<T> upstreamFailure(
            String mcpSessionId, LmsApiException exception) {
        int statusCode = exception.getStatusCode();
        boolean retryable = statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
        String code = retryable ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_PROTOCOL_CHANGED";
        return McpPrivateToolResponse.outcome(
                code,
                mcpSessionId,
                McpProviderType.LMS.name(),
                null,
                "LMS 서버에서 요청한 정보를 가져오지 못했어요. 잠시 후 다시 시도해 주세요.",
                code + ". LMS request failed.",
                retryable);
    }
}
