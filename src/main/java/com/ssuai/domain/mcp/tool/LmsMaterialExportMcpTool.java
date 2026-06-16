package com.ssuai.domain.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.action.ActionService.ActionExpiredException;
import com.ssuai.domain.action.ActionService.NoPendingActionException;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.LmsExportConfirmResponse;
import com.ssuai.domain.lms.dto.LmsExportPrepareResponse;
import com.ssuai.domain.lms.service.LmsMaterialExportService;
import com.ssuai.global.exception.LmsSessionExpiredException;

@Component
public class LmsMaterialExportMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LmsMaterialExportMcpTool.class);

    private final LmsMaterialExportService exportService;
    private final McpAuthHelper authHelper;

    public LmsMaterialExportMcpTool(LmsMaterialExportService exportService, McpAuthHelper authHelper) {
        this.exportService = exportService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "prepare_lms_material_export",
            description = "사용자가 선택한 LMS 주차학습 자료들(PDF, PPT 등)의 내보내기를 검증하고 대기열 등록을 준비합니다. "
                    + "이 도구 호출 전에 반드시 get_my_lms_courses와 get_my_lms_materials를 사용하여 exact content_id 목록을 확보해야 합니다. "
                    + "확보된 content_id들을 content_ids 파라미터로 전달해 주세요. 한도 초과 또는 미지원 파일은 자동 제외되고 안내됩니다. "
                    + "mcp_session_id with LMS provider linked required."
    )
    public McpPrivateToolResponse<Object> prepareLmsMaterialExport(
            @ToolParam(description = "MCP session ID with LMS linked.")
            String mcp_session_id,
            @ToolParam(description = "내보내기할 LMS 자료의 content_id 목록.")
            List<String> content_ids,
            @ToolParam(required = false, description = "조회할 학기 ID (선택).")
            Long term_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    try {
                        LmsExportPrepareResponse prepareResponse = exportService.prepare(studentId, term_id, content_ids);
                        return McpPrivateToolResponse.ok(mcp_session_id, (Object) prepareResponse);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    }
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }

    @Tool(
            name = "confirm_lms_material_export",
            description = "대기 상태인 LMS 자료 내보내기 액션을 실행 승인합니다. "
                    + "승인 성공 시 비동기 ZIP 압축 빌드 작업이 큐에 쌓이고, 20분간 유효한 capability URL 다운로드 링크가 반환됩니다. "
                    + "mcp_session_id with LMS provider linked required."
    )
    public McpPrivateToolResponse<Object> confirmLmsMaterialExport(
            @ToolParam(description = "MCP session ID with LMS linked.")
            String mcp_session_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    try {
                        LmsExportConfirmResponse confirmResponse = exportService.confirm(studentId);
                        return McpPrivateToolResponse.ok(mcp_session_id, (Object) confirmResponse);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    } catch (NoPendingActionException e) {
                        return McpPrivateToolResponse.ok(mcp_session_id, (Object) "대기 중인 내보내기 요청이 없습니다. prepare_lms_material_export를 먼저 호출해주세요.");
                    } catch (ActionExpiredException e) {
                        return McpPrivateToolResponse.ok(mcp_session_id, (Object) "내보내기 요청이 만료되었습니다. prepare_lms_material_export를 다시 호출해주세요.");
                    }
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }
}
