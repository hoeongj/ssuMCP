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
            description = "인증된 학생의 u-SAINT 시간표를 과목별로 묶어 조회합니다. "
                    + "year와 term을 생략하면 현재 u-SAINT에서 선택된 학기를 반환하며, "
                    + "특정 학기를 조회하려면 year와 term을 모두 전달하세요. "
                    + "term 값: 1=봄학기, 2=여름학기, 3=가을학기, 4=겨울학기. "
                    + "mcp_session_id 필요(SAINT 로그인). "
                    + "미인증 시 loginUrl이 포함된 AUTH_REQUIRED를 반환하므로, loginUrl을 사용자에게 보여주고 "
                    + "브라우저에서 로그인하도록 안내한 뒤 발급된 mcp_session_id로 다시 호출하세요."
    )
    public McpPrivateToolResponse<ScheduleResponse> getMySchedule(
            @ToolParam(required = false, description = "조회할 학년도(예: 2026). term과 함께 지정해야 함.")
            Integer year,
            @ToolParam(required = false, description = "학기: 1=봄, 2=여름, 3=가을, 4=겨울. year와 함께 지정해야 함.")
            Integer term,
            @ToolParam(description = "start_auth(SAINT)로 발급받은 MCP session ID. 없거나 SAINT 미연동이면 loginUrl과 함께 AUTH_REQUIRED를 반환.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.SAINT)
                .map(principal -> {
                    log.debug("get_my_schedule: fetching schedule");
                    ScheduleResponse data = scheduleService.fetchSchedule(principal.studentId(), year, term);
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.SAINT.name(), data);
                })
                .orElseGet(() -> {
                    log.debug("get_my_schedule: SAINT not linked, returning AUTH_REQUIRED");
                    return authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT);
                });
    }
}
