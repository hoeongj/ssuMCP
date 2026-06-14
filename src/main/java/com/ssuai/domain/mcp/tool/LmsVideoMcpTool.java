package com.ssuai.domain.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.video.dto.CourseWithLectures;
import com.ssuai.domain.lms.video.dto.LectureTranscriptResponse;
import com.ssuai.domain.lms.video.service.LmsVideoService;

@Component
public class LmsVideoMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LmsVideoMcpTool.class);

    private final LmsVideoService videoService;
    private final McpAuthHelper authHelper;

    public LmsVideoMcpTool(LmsVideoService videoService, McpAuthHelper authHelper) {
        this.videoService = videoService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_lms_terms",
            description = "사용자의 LMS 등록 학기 목록을 반환합니다. "
                    + "각 학기의 id, name, 시작/종료 날짜, 현재 기본 학기 여부를 포함합니다. "
                    + "반환된 id를 get_my_lecture_list 또는 get_my_assignments의 term_id 파라미터에 사용하세요. "
                    + "mcp_session_id with LMS provider required."
    )
    public McpPrivateToolResponse<Object> getMyLmsTerms(
            @ToolParam(description = "MCP session ID with LMS linked via start_auth(LMS).")
            String mcp_session_id) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    log.debug("get_my_lms_terms: fetching LMS terms");
                    List<LmsTermItem> terms = videoService.getTerms(studentId);
                    return McpPrivateToolResponse.ok(mcp_session_id, (Object) terms);
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }

    @Tool(
            name = "get_my_lecture_list",
            description = "LMS에 등록된 과목별 동영상 강의 목록을 반환합니다. "
                    + "각 강의는 content_id와 제목, 주차 정보를 포함합니다. "
                    + "get_lecture_transcript 호출에 필요한 content_id를 여기서 확인할 수 있습니다. "
                    + "term_id를 지정하지 않으면 LMS 기본 학기(현재 활성 학기)를 사용합니다. "
                    + "다른 학기의 강의를 보려면 get_my_lms_terms로 학기 목록을 먼저 조회하세요. "
                    + "mcp_session_id with LMS provider required. "
                    + "Returns AUTH_REQUIRED with loginUrl if not authenticated."
    )
    public McpPrivateToolResponse<Object> getMyLectureList(
            @ToolParam(description = "MCP session ID with LMS linked via start_auth(LMS).")
            String mcp_session_id,
            @ToolParam(required = false,
                    description = "조회할 학기 ID (get_my_lms_terms에서 반환된 id). "
                            + "null이면 LMS 기본 학기 사용.")
            Long term_id) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    log.debug("get_my_lecture_list: termId={}", term_id);
                    List<CourseWithLectures> list = videoService.getLectureList(studentId, term_id);
                    return McpPrivateToolResponse.ok(mcp_session_id, (Object) list);
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }

    @Tool(
            name = "get_lecture_transcript",
            description = "동영상 강의의 자막 또는 STT 변환 텍스트를 반환합니다. "
                    + "교수가 자막을 올렸으면 자막을 사용하고, 없으면 Groq Whisper AI로 자동 음성 변환합니다. "
                    + "주의: STT 변환은 강의 길이에 따라 수 분이 걸릴 수 있습니다. "
                    + "content_id는 get_my_lecture_list에서 확인하거나 LMS URL에서 직접 확인할 수 있습니다. "
                    + "mcp_session_id with LMS provider required."
    )
    public McpPrivateToolResponse<Object> getLectureTranscript(
            @ToolParam(description = "MCP session ID with LMS linked.")
            String mcp_session_id,
            @ToolParam(description = "LMS content ID (e.g. '60b9b2d69431f'). From get_my_lecture_list or the LMS URL.")
            String content_id) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    log.debug("get_lecture_transcript: fetching transcript");
                    LectureTranscriptResponse result = videoService.getTranscript(studentId, content_id);
                    return McpPrivateToolResponse.ok(mcp_session_id, (Object) result);
                })
                .orElseGet(() -> authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS));
    }
}
