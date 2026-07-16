package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;
import java.util.Set;

import com.ssuai.domain.auth.mcp.McpProviderType;

public record McpWebSessionResponse(
        String mcpSessionId,
        Instant expiresAt,
        Set<McpProviderType> linkedProviders) {

    public McpWebSessionResponse {
        linkedProviders = Set.copyOf(linkedProviders);
    }
}
