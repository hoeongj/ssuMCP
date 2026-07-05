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
            description = "인증된 학생의 u-SAINT 누적 성적을 학기별 GPA 이력과 함께 조회합니다. "
                    + "mcp_session_id 필요(SAINT 로그인). "
                    + "미인증 시 loginUrl이 포함된 AUTH_REQUIRED를 반환하므로, loginUrl을 사용자에게 보여주고 "
                    + "브라우저에서 로그인하도록 안내한 뒤 발급된 mcp_session_id로 다시 호출하세요."
    )
    public McpPrivateToolResponse<GradesResponse> getMyGrades(
            @ToolParam(description = "start_auth(SAINT)로 발급받은 MCP session ID. 없거나 SAINT 미연동이면 loginUrl과 함께 AUTH_REQUIRED를 반환.")
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
