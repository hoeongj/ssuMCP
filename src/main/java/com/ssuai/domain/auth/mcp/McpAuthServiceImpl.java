package com.ssuai.domain.auth.mcp;

import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
class McpAuthServiceImpl implements McpAuthService {

    private final McpAuthSessionStore sessionStore;
    private final McpAuthStateStore stateStore;

    McpAuthServiceImpl(McpAuthSessionStore sessionStore, McpAuthStateStore stateStore) {
        this.sessionStore = sessionStore;
        this.stateStore = stateStore;
    }

    @Override
    public Optional<McpAuthSession> find(String idValue) {
        return sessionStore.find(idValue);
    }

    @Override
    public McpAuthSession getOrCreate(String idValue) {
        if (idValue != null && !idValue.isBlank()) {
            Optional<McpAuthSession> existing = sessionStore.find(idValue);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return sessionStore.create();
    }

    @Override
    public McpAuthSession createSession() {
        return sessionStore.create();
    }

    @Override
    public McpAuthStateEntry generateState(McpAuthSessionId sessionId, McpProviderType provider) {
        return stateStore.generate(sessionId, provider);
    }

    @Override
    public Optional<McpAuthStateEntry> consumeState(String state) {
        return stateStore.consume(state);
    }

    @Override
    public void linkProvider(McpAuthSessionId sessionId, McpProviderType provider, String principalKey) {
        sessionStore.linkProvider(sessionId, provider, principalKey);
    }

    @Override
    public void unlinkProvider(McpAuthSessionId sessionId, McpProviderType provider) {
        sessionStore.unlinkProvider(sessionId, provider);
    }

    @Override
    public void invalidateSession(McpAuthSessionId sessionId) {
        sessionStore.invalidate(sessionId);
    }
}
