package com.ssuai.domain.auth.mcp;

import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.library.auth.LibrarySessionStore;

/** Revokes and reports the credential namespace referenced by one exact MCP provider link. */
@Service
public class McpProviderCredentialService {

    private final SaintSessionStore saintSessions;
    private final LmsSessionStore lmsSessions;
    private final LibrarySessionStore librarySessions;

    public McpProviderCredentialService(
            SaintSessionStore saintSessions,
            LmsSessionStore lmsSessions,
            LibrarySessionStore librarySessions) {
        this.saintSessions = saintSessions;
        this.lmsSessions = lmsSessions;
        this.librarySessions = librarySessions;
    }

    public void invalidate(McpProviderLink link) {
        if (link == null) {
            return;
        }
        switch (link.provider()) {
            case SAINT -> saintSessions.invalidate(link.principalKey());
            case LMS -> lmsSessions.invalidate(link.principalKey());
            case LIBRARY -> librarySessions.invalidate(link.principalKey());
        }
    }

    public void invalidate(McpProviderType provider, String credentialKey) {
        if (provider == null || credentialKey == null || credentialKey.isBlank()) {
            return;
        }
        switch (provider) {
            case SAINT -> saintSessions.invalidate(credentialKey);
            case LMS -> lmsSessions.invalidate(credentialKey);
            case LIBRARY -> librarySessions.invalidate(credentialKey);
        }
    }

    public McpProviderHealthSnapshot health(McpProviderLink link) {
        if (link == null) {
            return McpProviderHealthSnapshot.unknown(0);
        }
        return switch (link.provider()) {
            case SAINT -> saintSessions.health(link.principalKey())
                    .orElseGet(() -> expired("AUTH_REQUIRED"));
            case LMS -> lmsSessions.health(link.principalKey())
                    .orElseGet(() -> expired("AUTH_REQUIRED"));
            case LIBRARY -> librarySessions.has(link.principalKey())
                    ? McpProviderHealthSnapshot.unknown(1)
                    : expired("AUTH_REQUIRED");
        };
    }

    /**
     * Reads health once and derives grant/availability from that same snapshot.
     * UNKNOWN remains available so a freshly captured credential can make its first call;
     * ERROR keeps the grant but is not advertised as operational.
     */
    public McpProviderCredentialStatus status(McpProviderLink link) {
        McpProviderHealthSnapshot snapshot = health(link);
        if (link == null) {
            return new McpProviderCredentialStatus(snapshot, false, false);
        }
        if (snapshot.health() == McpProviderHealth.EXPIRED) {
            return new McpProviderCredentialStatus(snapshot, false, false);
        }
        boolean credentialExists = switch (link.provider()) {
            case SAINT -> saintSessions.session(link.principalKey()).isPresent();
            case LMS -> lmsSessions.session(link.principalKey()).isPresent();
            case LIBRARY -> true;
        };
        boolean linked = credentialExists;
        boolean available = linked && snapshot.health() != McpProviderHealth.ERROR;
        return new McpProviderCredentialStatus(snapshot, linked, available);
    }

    public boolean isAvailable(McpProviderLink link) {
        return status(link).available();
    }

    private static McpProviderHealthSnapshot expired(String failureCode) {
        return new McpProviderHealthSnapshot(
                McpProviderHealth.EXPIRED, null, null, null, failureCode, 0);
    }
}
