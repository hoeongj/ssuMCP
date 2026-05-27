package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.service.SaintScheduleService;

/**
 * MCP tool for the authenticated student's u-SAINT timetable (Task 18 Slice C).
 *
 * <p>External MCP clients (Claude Desktop, Cursor) pass {@code mcp_session_id} to
 * identify their auth session. If the SAINT provider is not yet linked, the tool
 * returns AUTH_REQUIRED with a loginUrl; the client opens the URL in a browser and
 * retries the call once authentication completes.
 *
 * <p>The chatbot path (LlmChatService) calls SaintScheduleService directly and does
 * not invoke this method.
 */
@Component
public class SaintScheduleMcpTool {

    private static final Logger log = LoggerFactory.getLogger(SaintScheduleMcpTool.class);

    private final SaintScheduleService scheduleService;
    private final McpAuthHelper authHelper;

    public SaintScheduleMcpTool(SaintScheduleService scheduleService, McpAuthHelper authHelper) {
        this.scheduleService = scheduleService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_schedule",
            description = "Returns the authenticated student's u-SAINT timetable for all enrolled semesters. "
                    + "Requires mcp_session_id with the SAINT provider linked via start_auth. "
                    + "Returns AUTH_REQUIRED with a loginUrl if SAINT is not authenticated — "
                    + "show the loginUrl to the user and ask them to open it in a browser, "
                    + "then retry this call with the returned mcp_session_id."
    )
    public McpPrivateToolResponse<ScheduleResponse> getMySchedule(
            @ToolParam(description = "MCP session ID issued by start_auth(SAINT). If absent or SAINT not linked, returns AUTH_REQUIRED with a loginUrl.")
            String mcp_session_id) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.SAINT)
                .map(studentId -> {
                    log.debug("get_my_schedule: fetching schedule");
                    ScheduleResponse data = scheduleService.fetchSchedule(studentId);
                    return McpPrivateToolResponse.ok(mcp_session_id, data);
                })
                .orElseGet(() -> {
                    log.debug("get_my_schedule: SAINT not linked, returning AUTH_REQUIRED");
                    return authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT);
                });
    }
}
