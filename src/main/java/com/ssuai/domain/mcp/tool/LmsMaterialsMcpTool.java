package com.ssuai.domain.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.LmsCourse;
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
            description = "인증된 사용자의 LMS 수강 과목 목록을 조회합니다. "
                    + "term_id를 지정하지 않으면 현재 날짜 기준 활성 학기가 자동 선택됩니다. "
                    + "mcp_session_id with LMS provider linked required."
    )
    public McpPrivateToolResponse<Object> getMyLmsCourses(
            @ToolParam(description = "MCP session ID with LMS linked via start_auth(LMS).")
            String mcp_session_id,
            @ToolParam(required = false, description = "조회할 학기 ID. 생략 시 현재 활성 학기가 선택됩니다.")
            Long term_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    try {
                        List<LmsCourse> courses = materialsService.listCourses(studentId, term_id);
                        return McpPrivateToolResponse.<Object>ok(mcp_session_id, courses);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    } catch (LmsApiException e) {
                        return McpPrivateToolResponse.<Object>ok(mcp_session_id,
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
                    + "mcp_session_id with LMS provider linked required."
    )
    public McpPrivateToolResponse<Object> getMyLmsMaterials(
            @ToolParam(description = "MCP session ID with LMS linked via start_auth(LMS).")
            String mcp_session_id,
            @ToolParam(description = "조회할 LMS 과목 ID 목록 (get_my_lms_courses에서 획득).")
            List<Long> course_ids,
            @ToolParam(required = false, description = "조회할 학기 ID (선택).")
            Long term_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    try {
                        List<LmsCourseMaterials> materials = materialsService.listMaterials(studentId, course_ids, term_id);
                        return McpPrivateToolResponse.<Object>ok(mcp_session_id, materials);
                    } catch (LmsSessionExpiredException e) {
                        return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                    } catch (LmsApiException e) {
                        return McpPrivateToolResponse.<Object>ok(mcp_session_id,
                                "LMS API 오류가 발생했습니다. 잠시 후 다시 시도해 주세요. (" + e.getMessage() + ")");
                    }
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }
}
