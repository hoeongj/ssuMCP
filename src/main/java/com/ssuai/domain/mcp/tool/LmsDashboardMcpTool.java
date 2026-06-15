package com.ssuai.domain.mcp.tool;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.service.LmsDashboardService;
import com.ssuai.global.exception.LmsSessionExpiredException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class LmsDashboardMcpTool {

    private final McpAuthHelper authHelper;
    private final LmsDashboardService dashboardService;

    public LmsDashboardMcpTool(McpAuthHelper authHelper, LmsDashboardService dashboardService) {
        this.authHelper = authHelper;
        this.dashboardService = dashboardService;
    }

    @Tool(
        name = "get_lms_dashboard",
        description = """
            LMS 대시보드: 인증된 사용자의 미제출 과제·퀴즈 마감, 학사일정(시험·수강신청 등),
            진행 중인 공지사항을 한 번에 요약합니다. term_id를 생략하면 현재 학기가 자동 선택됩니다.
            인증이 필요합니다(start_auth provider=LMS 후 mcp_session_id 전달).
            """
    )
    public McpPrivateToolResponse<Object> getLmsDashboard(
            @ToolParam(description = "MCP 세션 ID (start_auth로 발급받은 값)")
            String mcp_session_id,
            @ToolParam(description = "학기 ID (선택). 생략 시 현재 학기 자동 선택.", required = false)
            Long term_id) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    try {
                        var dashboard = dashboardService.getDashboard(studentId, term_id);
                        return McpPrivateToolResponse.<Object>ok(mcp_session_id, dashboard);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    }
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }
}
