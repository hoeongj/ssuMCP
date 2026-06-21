package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.service.SaintGradesService;

/**
 * MCP tool for the authenticated student's u-SAINT cumulative grades (Task 18 Slice C).
 *
 * <p>Same auth model as {@link SaintScheduleMcpTool}: requires mcp_session_id with SAINT
 * provider linked. Returns AUTH_REQUIRED with a loginUrl otherwise.
 *
 * <p>The chatbot path (LlmChatService) calls SaintGradesService directly and does
 * not invoke this method.
 */
@Component
public class SaintGradesMcpTool {

    private static final Logger log = LoggerFactory.getLogger(SaintGradesMcpTool.class);

    private final SaintGradesService gradesService;
    private final McpAuthHelper authHelper;

    public SaintGradesMcpTool(SaintGradesService gradesService, McpAuthHelper authHelper) {
        this.gradesService = gradesService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_grades",
            description = "Returns the authenticated student's cumulative u-SAINT grades including semester GPA history. "
                    + "Requires mcp_session_id with the SAINT provider linked via start_auth. "
                    + "Returns AUTH_REQUIRED with a loginUrl if SAINT is not authenticated — "
                    + "show the loginUrl to the user and ask them to open it in a browser, "
                    + "then retry this call with the returned mcp_session_id."
    )
    public McpPrivateToolResponse<GradesResponse> getMyGrades(
            @ToolParam(description = "MCP session ID issued by start_auth(SAINT). If absent or SAINT not linked, returns AUTH_REQUIRED with a loginUrl.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.SAINT)
                .map(principal -> {
                    log.debug("get_my_grades: fetching grades");
                    GradesResponse data = gradesService.fetchGrades(principal.studentId());
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.SAINT.name(), data);
                })
                .orElseGet(() -> {
                    log.debug("get_my_grades: SAINT not linked, returning AUTH_REQUIRED");
                    return authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT);
                });
    }
}
