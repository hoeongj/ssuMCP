package com.ssuai.domain.mcp.tool;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.service.LmsDashboardService;
import com.ssuai.global.exception.LmsApiException;
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
            SSU Campus(숭실대학교 캠퍼스) LMS 대시보드: 인증된 사용자의 미제출 과제·퀴즈 마감, 학사일정(시험·수강신청 등),
            진행 중인 공지사항을 한 번에 요약합니다. term_id를 생략하면 현재 학기가 자동 선택됩니다.
            인증이 필요합니다(start_auth provider=LMS 후 mcp_session_id 전달).
            """
    )
    public McpPrivateToolResponse<Object> getLmsDashboard(
            @ToolParam(required = false, description = "선택 MCP session ID. 생략하면 현재 MCP transport에 안전하게 바인딩된 세션을 사용합니다.")
            String mcp_session_id,
            @ToolParam(description = "학기 ID (선택). 생략 시 현재 학기 자동 선택.", required = false)
            Long term_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LMS)
                .map(principal -> {
                    try {
                        var dashboard = dashboardService.getDashboard(principal.providerSessionKey(), term_id);
                        return McpPrivateToolResponse.<Object>ok(
                                principal.sessionId(), McpProviderType.LMS.name(), dashboard);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    } catch (LmsApiException e) {
                        return LmsMcpToolResponse.<Object>upstreamFailure(
                                principal.sessionId(), e);
                    }
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }
}
