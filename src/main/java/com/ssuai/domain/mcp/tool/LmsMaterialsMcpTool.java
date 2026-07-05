package com.ssuai.domain.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.LmsCourseMaterials;
import com.ssuai.domain.lms.service.LmsMaterialsService;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

@Component
public class LmsMaterialsMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LmsMaterialsMcpTool.class);

    private final LmsMaterialsService materialsService;
    private final McpAuthHelper authHelper;

    public LmsMaterialsMcpTool(LmsMaterialsService materialsService, McpAuthHelper authHelper) {
        this.materialsService = materialsService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_lms_courses",
            description = "인증된 사용자의 LMS 수강 과목을 **각 과목의 다운로드 가능한 자료(파일)와 함께** 한 번에 조회합니다. "
                    + "로그인 직후 이 도구 하나만 호출하면 과목별로 파일 수·총 용량·확장자별 그룹·각 파일의 content_id가 모두 반환됩니다. "
                    + "사용자에게는 '과목 | 파일 수 | 용량' 표(파일이 있는 과목만)와 합계를 보여주고 어떤 과목을 받을지 물어보세요. "
                    + "사용자가 과목을 고르면 이 응답에 들어있는 content_id로 바로 prepare_lms_material_export를 호출하면 됩니다(get_my_lms_materials를 다시 부를 필요 없음). "
                    + "term_id를 지정하지 않으면 현재 날짜 기준 활성 학기가 자동 선택됩니다. "
                    + "mcp_session_id 필요(LMS 로그인)."
    )
    public McpPrivateToolResponse<Object> getMyLmsCourses(
            @ToolParam(description = "start_auth(LMS)로 LMS를 연동한 MCP session ID.")
            String mcp_session_id,
            @ToolParam(required = false, description = "조회할 학기 ID. 생략 시 현재 활성 학기가 선택됩니다.")
            Long term_id
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LMS)
                .map(principal -> {
                    try {
                        // Fetch every course WITH its filtered materials (groups, counts, sizes,
                        // content_ids) in one shot so the user sees course + files together right
                        // after login. courseIds=null → all courses.
                        List<LmsCourseMaterials> courses = materialsService.listMaterials(principal.studentId(), null, term_id);
                        return McpPrivateToolResponse.<Object>ok(
                                principal.sessionId(), McpProviderType.LMS.name(), courses);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    } catch (LmsApiException e) {
                        return McpPrivateToolResponse.<Object>ok(
                                principal.sessionId(), McpProviderType.LMS.name(),
                                "LMS API 오류가 발생했습니다. 잠시 후 다시 시도해 주세요. (" + e.getMessage() + ")");
                    }
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }

    @Tool(
            name = "get_my_lms_materials",
            description = "지정된 LMS 과목들의 주차학습 비디오 외 주차별 학습 자료(PDF, PPT, DOC, HWP, TXT 등) 목록을 조회합니다. "
                    + "결과는 파일 확장자별 그룹(개수 및 상세 목록)으로 분류되어 제공되며, 비디오 및 오디오 파일은 보안 및 용량 제한으로 포함되지 않습니다. "
                    + "term_id를 지정하지 않으면 현재 활성 학기가 자동 선택됩니다. "
                    + "mcp_session_id 필요(LMS 로그인)."
    )
    public McpPrivateToolResponse<Object> getMyLmsMaterials(
            @ToolParam(description = "start_auth(LMS)로 LMS를 연동한 MCP session ID.")
            String mcp_session_id,
            @ToolParam(description = "조회할 LMS 과목 ID 목록 (get_my_lms_courses에서 획득).")
            List<Long> course_ids,
            @ToolParam(required = false, description = "조회할 학기 ID (선택).")
            Long term_id
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LMS)
                .map(principal -> {
                    try {
                        List<LmsCourseMaterials> materials = materialsService.listMaterials(principal.studentId(), course_ids, term_id);
                        return McpPrivateToolResponse.<Object>ok(
                                principal.sessionId(), McpProviderType.LMS.name(), materials);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    } catch (LmsApiException e) {
                        return McpPrivateToolResponse.<Object>ok(
                                principal.sessionId(), McpProviderType.LMS.name(),
                                "LMS API 오류가 발생했습니다. 잠시 후 다시 시도해 주세요. (" + e.getMessage() + ")");
                    }
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }
}
