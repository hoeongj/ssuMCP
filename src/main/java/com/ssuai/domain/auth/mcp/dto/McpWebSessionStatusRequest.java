package com.ssuai.domain.auth.mcp.dto;

import jakarta.validation.constraints.NotBlank;

/** Browser-held MCP capability whose current provider grants should be re-read. */
public record McpWebSessionStatusRequest(
        @NotBlank String mcpSessionId) {
}
