package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.ssuai.domain.auth.mcp.McpProviderHealth;
import com.ssuai.domain.auth.mcp.McpProviderType;

public record McpWebSessionResponse(
        String mcpSessionId,
        Instant expiresAt,
        Set<McpProviderType> linkedProviders,
        Set<McpProviderType> availableProviders,
        Map<McpProviderType, McpProviderHealth> providerHealth) {

    public McpWebSessionResponse {
        linkedProviders = Set.copyOf(linkedProviders);
        availableProviders = Set.copyOf(availableProviders);
        providerHealth = Map.copyOf(providerHealth);
    }
}
