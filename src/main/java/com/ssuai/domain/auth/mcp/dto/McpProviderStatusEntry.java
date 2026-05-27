package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;

import com.ssuai.domain.auth.mcp.McpProviderType;

/**
 * Auth status for one provider inside {@link McpAuthStatusResponse}.
 * {@code linkedAt} is null when the provider is not linked.
 */
public record McpProviderStatusEntry(McpProviderType provider, boolean linked, Instant linkedAt) {

    public static McpProviderStatusEntry linked(McpProviderType provider, Instant linkedAt) {
        return new McpProviderStatusEntry(provider, true, linkedAt);
    }

    public static McpProviderStatusEntry notLinked(McpProviderType provider) {
        return new McpProviderStatusEntry(provider, false, null);
    }
}
