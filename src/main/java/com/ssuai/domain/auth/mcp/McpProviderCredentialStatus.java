package com.ssuai.domain.auth.mcp;

import java.util.Objects;

/** One internally consistent snapshot of a provider grant and its operational health. */
public record McpProviderCredentialStatus(
        McpProviderHealthSnapshot health,
        boolean linked,
        boolean available) {

    public McpProviderCredentialStatus {
        Objects.requireNonNull(health, "health required");
        if (available && !linked) {
            throw new IllegalArgumentException("an available credential must be linked");
        }
    }
}
