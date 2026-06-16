package com.ssuai.domain.mcp.tool;

import java.time.Instant;
import java.util.Optional;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * SPIKE TOOL — throwaway. Remove in follow-up PR after smoke test.
 * Tests elicitation capability and transport session stability.
 */
@Component
@Profile("!test")
public class DiagnosticMcpTool {

    @Tool(description = "SPIKE: Reports MCP exchange session-id and elicitation capability. Call twice in a row and compare session_id_prefix values to confirm transport session stability. Delete after spike.")
    public String check_elicitation_support(ToolContext toolContext) {
        Optional<McpSyncServerExchange> exchangeOpt = McpToolUtils.getMcpExchange(toolContext);
        if (exchangeOpt.isEmpty()) {
            return "ERROR: No McpSyncServerExchange found in ToolContext";
        }
        McpSyncServerExchange exchange = exchangeOpt.get();

        // transport session id — first 12 chars only (fingerprint, not full value)
        String sid = exchange.sessionId();
        String sidPrefix = (sid != null && sid.length() >= 12)
                ? sid.substring(0, 12) + "..."
                : String.valueOf(sid);

        // elicitation capability
        McpSchema.ClientCapabilities caps = exchange.getClientCapabilities();
        boolean elicitSupported = caps != null && caps.elicitation() != null;

        return String.format(
                "session_id_prefix=%s | elicitation_supported=%s | timestamp=%s",
                sidPrefix, elicitSupported, Instant.now());
    }
}
