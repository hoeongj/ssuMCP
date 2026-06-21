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
            description = "Returns the authenticated student's u-SAINT chapel attendance information. "
                    + "Year and semester are optional; without them the current u-SAINT semester is used. "
                    + "Requires mcp_session_id with the SAINT provider linked via start_auth."
    )
    public McpPrivateToolResponse<ChapelInfo> getMyChapelInfo(
            @ToolParam(required = false, description = "Academic year, such as 2026.") Integer year,
            @ToolParam(required = false, description = "Semester: 1학기, 여름학기, 2학기, or 겨울학기.") String semester,
            @ToolParam(description = "MCP session ID issued by start_auth(SAINT). If absent or SAINT not linked, returns AUTH_REQUIRED with a loginUrl.")
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
            description = "Returns the authenticated student's u-SAINT graduation eligibility and requirement status. "
                    + "Requires mcp_session_id with the SAINT provider linked via start_auth."
    )
    public McpPrivateToolResponse<GraduationStatus> checkGraduationRequirements(
            @ToolParam(description = "MCP session ID issued by start_auth(SAINT). If absent or SAINT not linked, returns AUTH_REQUIRED with a loginUrl.")
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
            description = "Returns the authenticated student's u-SAINT scholarship receipt history. "
                    + "Year is optional; without it all available history is returned. "
                    + "Requires mcp_session_id with the SAINT provider linked via start_auth."
    )
    public McpPrivateToolResponse<List<ScholarshipEntry>> getMyScholarships(
            @ToolParam(required = false, description = "Academic year, such as 2026.") Integer year,
            @ToolParam(description = "MCP session ID issued by start_auth(SAINT). If absent or SAINT not linked, returns AUTH_REQUIRED with a loginUrl.")
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
            description = "Simulates cumulative GPA from the authenticated student's u-SAINT academicRecord. "
                    + "Use plannedCredits with either plannedGradePointAverage to project a GPA, targetGpa to compute the required average, or both. "
                    + "Uses SSU's 4.5 max grade-point scale and excludes P/F credits from the GPA denominator. "
                    + "Requires mcp_session_id with the SAINT provider linked via start_auth."
    )
    public McpPrivateToolResponse<GpaSimulationResponse> simulateGpa(
            @ToolParam(description = "GPA-bearing credits to add, excluding P/F credits.")
            Double plannedCredits,
            @ToolParam(required = false, description = "Expected average grade point for plannedCredits, 0.0 to 4.5.")
            Double plannedGradePointAverage,
            @ToolParam(required = false, description = "Target cumulative GPA, 0.0 to 4.5.")
            Double targetGpa,
            @ToolParam(description = "MCP session ID issued by start_auth(SAINT). If absent or SAINT not linked, returns AUTH_REQUIRED with a loginUrl.")
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
