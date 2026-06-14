package com.ssuai.domain.auth.mcp.dto;

import java.time.Instant;

public record McpWebSessionResponse(String mcpSessionId, Instant expiresAt) {}
