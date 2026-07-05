package com.ssuai.domain.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GpaSimulationResponse;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.domain.saint.service.SaintChapelService;
import com.ssuai.domain.saint.service.SaintGpaSimulationService;
import com.ssuai.domain.saint.service.SaintGraduationService;
import com.ssuai.domain.saint.service.SaintScholarshipService;

@Component
public class SaintExtendedMcpTools {

    private static final Logger log = LoggerFactory.getLogger(SaintExtendedMcpTools.class);

    private final SaintChapelService chapelService;
    private final SaintGraduationService graduationService;
    private final SaintScholarshipService scholarshipService;
    private final SaintGpaSimulationService gpaSimulationService;
    private final McpAuthHelper authHelper;

    public SaintExtendedMcpTools(
            SaintChapelService chapelService,
            SaintGraduationService graduationService,
            SaintScholarshipService scholarshipService,
            SaintGpaSimulationService gpaSimulationService,
            McpAuthHelper authHelper) {
        this.chapelService = chapelService;
        this.graduationService = graduationService;
        this.scholarshipService = scholarshipService;
        this.gpaSimulationService = gpaSimulationService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_chapel_info",
            description = "인증된 학생의 u-SAINT 채플 출석 정보를 조회합니다. "
                    + "year와 semester는 선택이며, 생략하면 현재 u-SAINT 학기를 사용합니다. "
                    + "mcp_session_id 필요(SAINT 로그인)."
    )
    public McpPrivateToolResponse<ChapelInfo> getMyChapelInfo(
            @ToolParam(required = false, description = "조회할 학년도(예: 2026).") Integer year,
            @ToolParam(required = false, description = "학기: 1학기, 여름학기, 2학기, 겨울학기 중 하나.") String semester,
            @ToolParam(description = "start_auth(SAINT)로 발급받은 MCP session ID. 없거나 SAINT 미연동이면 loginUrl과 함께 AUTH_REQUIRED를 반환.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.SAINT)
                .map(principal -> {
                    log.debug("get_my_chapel_info: fetching chapel information");
                    ChapelInfo data = chapelService.fetchChapelInfo(principal.studentId(), year, semester);
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.SAINT.name(), data);
                })
                .orElseGet(() -> authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT));
    }

    @Tool(
            name = "check_graduation_requirements",
            description = "인증된 학생의 u-SAINT 졸업 가능 여부와 졸업요건 충족 현황을 조회합니다. "
                    + "mcp_session_id 필요(SAINT 로그인)."
    )
    public McpPrivateToolResponse<GraduationStatus> checkGraduationRequirements(
            @ToolParam(description = "start_auth(SAINT)로 발급받은 MCP session ID. 없거나 SAINT 미연동이면 loginUrl과 함께 AUTH_REQUIRED를 반환.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.SAINT)
                .map(principal -> {
                    log.debug("check_graduation_requirements: fetching status");
                    GraduationStatus data = graduationService.fetchGraduationRequirements(principal.studentId());
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.SAINT.name(), data);
                })
                .orElseGet(() -> authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT));
    }

    @Tool(
            name = "get_my_scholarships",
            description = "인증된 학생의 u-SAINT 장학금 수혜 내역을 조회합니다. "
                    + "year는 선택이며, 생략하면 조회 가능한 전체 내역을 반환합니다. "
                    + "mcp_session_id 필요(SAINT 로그인)."
    )
    public McpPrivateToolResponse<List<ScholarshipEntry>> getMyScholarships(
            @ToolParam(required = false, description = "조회할 학년도(예: 2026).") Integer year,
            @ToolParam(description = "start_auth(SAINT)로 발급받은 MCP session ID. 없거나 SAINT 미연동이면 loginUrl과 함께 AUTH_REQUIRED를 반환.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.SAINT)
                .map(principal -> {
                    log.debug("get_my_scholarships: fetching history");
                    List<ScholarshipEntry> data = scholarshipService.fetchScholarships(principal.studentId(), year);
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.SAINT.name(), data);
                })
                .orElseGet(() -> authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT));
    }

    @Tool(
            name = "simulate_gpa",
            description = "인증된 학생의 u-SAINT academicRecord를 기반으로 누적 GPA를 시뮬레이션합니다. "
                    + "plannedCredits와 함께 plannedGradePointAverage를 넣어 예상 GPA를 계산하거나, targetGpa를 넣어 필요한 평균 평점을 계산하거나, 둘 다 사용할 수 있습니다. "
                    + "숭실대학교의 4.5 만점 평점 척도를 사용하며 P/F 학점은 GPA 분모에서 제외합니다. "
                    + "mcp_session_id 필요(SAINT 로그인)."
    )
    public McpPrivateToolResponse<GpaSimulationResponse> simulateGpa(
            @ToolParam(description = "추가할 성적 산입 학점(P/F 학점 제외).")
            Double plannedCredits,
            @ToolParam(required = false, description = "plannedCredits의 예상 평균 평점(0.0~4.5).")
            Double plannedGradePointAverage,
            @ToolParam(required = false, description = "목표 누적 GPA(0.0~4.5).")
            Double targetGpa,
            @ToolParam(description = "start_auth(SAINT)로 발급받은 MCP session ID. 없거나 SAINT 미연동이면 loginUrl과 함께 AUTH_REQUIRED를 반환.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.SAINT)
                .map(principal -> {
                    log.debug("simulate_gpa: simulating GPA");
                    GpaSimulationResponse data = gpaSimulationService.simulate(
                            principal.studentId(),
                            plannedCredits == null ? 0.0d : plannedCredits,
                            plannedGradePointAverage,
                            targetGpa);
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.SAINT.name(), data);
                })
                .orElseGet(() -> authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT));
    }
}
