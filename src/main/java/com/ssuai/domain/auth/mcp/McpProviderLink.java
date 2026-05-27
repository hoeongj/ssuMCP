package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Objects;

/**
 * A linked provider session inside a {@link McpAuthSession}.
 *
 * <p>{@code principalKey} is the key used to look up the actual credentials in
 * the provider-specific store:
 * <ul>
 *   <li>SAINT / LMS → studentId (used as key in SaintSessionStore / LmsSessionStore)
 *   <li>LIBRARY → a random opaque session key (used as key in LibrarySessionStore)
 * </ul>
 *
 * <p>The principalKey must not be logged in plain — pass it through
 * {@link com.ssuai.domain.auth.saint.SaintSessionStore#fingerprint} for log output.
 */
public record McpProviderLink(McpProviderType provider, String principalKey, Instant linkedAt) {

    public McpProviderLink {
        Objects.requireNonNull(provider, "provider required");
        Objects.requireNonNull(principalKey, "principalKey required");
        if (principalKey.isBlank()) {
            throw new IllegalArgumentException("principalKey must not be blank");
        }
        Objects.requireNonNull(linkedAt, "linkedAt required");
    }
}
