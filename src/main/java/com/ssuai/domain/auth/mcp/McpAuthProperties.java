package com.ssuai.domain.auth.mcp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the MCP auth session subsystem.
 *
 * <pre>
 * ssuai:
 *   mcp:
 *     auth:
 *       session-ttl: 4h        # how long a McpAuthSession lives after creation
 *       max-sessions: 500      # LRU cap on McpAuthSessionStore
 *       state-ttl: 10m         # how long a one-time login state is valid
 *       max-states: 1000       # LRU cap on McpAuthStateStore
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "ssuai.mcp.auth")
public class McpAuthProperties {

    private Duration sessionTtl = Duration.ofHours(4);
    private int maxSessions = 500;
    private Duration stateTtl = Duration.ofMinutes(10);
    private int maxStates = 1000;

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public void setStateTtl(Duration stateTtl) {
        this.stateTtl = stateTtl;
    }

    public int getMaxStates() {
        return maxStates;
    }

    public void setMaxStates(int maxStates) {
        this.maxStates = maxStates;
    }
}
