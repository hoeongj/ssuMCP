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
 *       session-ttl: 7d   # how long a McpAuthSession lives after creation
 *       state-ttl: 10m    # how long a one-time login state is valid
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "ssuai.mcp.auth")
public class McpAuthProperties {

    private Duration sessionTtl = Duration.ofDays(7);
    private Duration stateTtl = Duration.ofMinutes(10);

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public void setStateTtl(Duration stateTtl) {
        this.stateTtl = stateTtl;
    }
}
