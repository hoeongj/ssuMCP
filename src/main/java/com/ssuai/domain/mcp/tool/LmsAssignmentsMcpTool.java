package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import java.util.List;
import com.ssuai.domain.lms.dto.AssignmentsCompactResponse;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.service.LmsAssignmentsService;
import com.ssuai.domain.lms.service.LmsTermResolver;

/**
 * MCP tool for the authenticated student's pending LMS assignments (Task 18 Slice C).
 *
 * <p>Requires mcp_session_id with the LMS provider linked. Returns AUTH_REQUIRED with
 * a loginUrl otherwise. The SAINT auth callback also links LMS as a best-effort, so a
 * single login usually covers both.
 *
 * <p>The chatbot path (LlmChatService) calls LmsAssignmentsService directly and does
 * not invoke this method.
 */
@Component
public class LmsAssignmentsMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LmsAssignmentsMcpTool.class);

    private final LmsAssignmentsService assignmentsService;
    private final McpAuthHelper authHelper;

    public LmsAssignmentsMcpTool(LmsAssignmentsService assignmentsService, McpAuthHelper authHelper) {
        this.assignmentsService = assignmentsService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_assignments",
            description = "Returns the authenticated student's pending LMS assignments and quizzes. "
                    + "Requires mcp_session_id with the LMS provider linked via start_auth. "
                    + "compact=true 지원. "
                    + "term_id를 지정하지 않으면 LMS 기본 학기 사용. "
                    + "다른 학기 과제를 조회하려면 get_my_lms_terms로 학기 목록을 먼저 확인하세요. "
                    + "Returns AUTH_REQUIRED with a loginUrl if LMS is not authenticated — "
                    + "show the loginUrl to the user and ask them to open it in a browser, "
                    + "then retry this call with the returned mcp_session_id."
    )
    public McpPrivateToolResponse<Object> getMyAssignments(
            @ToolParam(description = "MCP session ID issued by start_auth(LMS). If absent or LMS not linked, returns AUTH_REQUIRED with a loginUrl.")
            String mcp_session_id,
            @ToolParam(description = "compact=true: 과제명·마감일만 반환. compact=false(기본): 상세 정보 포함.", required = false)
            Boolean compact,
            @ToolParam(required = false,
                    description = "조회할 학기 ID (get_my_lms_terms에서 반환된 id). null이면 LMS 기본 학기 사용.")
            Long term_id) {
        boolean isCompact = Boolean.TRUE.equals(compact);
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LMS)
                .map(principal -> {
                    log.debug("get_my_assignments: termId={}", term_id);
                    AssignmentsResponse data = assignmentsService.fetchAssignments(principal.studentId(), term_id);
                    Object payload = isCompact
                            ? AssignmentsCompactResponse.from(data)
                            : data;
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.LMS.name(), payload);
                })
                .orElseGet(() -> {
                    log.debug("get_my_assignments: LMS not linked, returning AUTH_REQUIRED");
                    return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                });
    }

    @Tool(
            name = "get_my_lms_terms",
            description = "사용자의 LMS 등록 학기 목록을 반환합니다. "
                    + "각 학기의 id, name, 시작/종료 날짜, 현재 기본 학기 여부(defaultTerm)를 포함합니다. "
                    + "defaultTerm=true는 현재 활성 학기 하나에만 표시됩니다(term_id 생략 시 이 학기가 사용됨). "
                    + "반환된 id를 get_my_lecture_list 또는 get_my_assignments의 term_id 파라미터에 사용하세요. "
                    + "mcp_session_id with LMS provider required."
    )
    public McpPrivateToolResponse<Object> getMyLmsTerms(
            @ToolParam(description = "MCP session ID with LMS linked via start_auth(LMS).")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LMS)
                .map(principal -> {
                    List<LmsTermItem> terms = LmsTermResolver.withResolvedDefault(
                            assignmentsService.fetchTerms(principal.studentId()));
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.LMS.name(), (Object) terms);
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }
}
