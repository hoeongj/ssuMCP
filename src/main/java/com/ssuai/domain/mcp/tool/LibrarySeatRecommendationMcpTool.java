package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.recommendation.LibrarySeatPreference;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationService;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Component
public class LibrarySeatRecommendationMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatRecommendationMcpTool.class);

    private final LibrarySeatRecommendationService recommendationService;
    private final McpAuthHelper authHelper;

    public LibrarySeatRecommendationMcpTool(
            LibrarySeatRecommendationService recommendationService,
            McpAuthHelper authHelper) {
        this.recommendationService = recommendationService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "recommend_library_seats",
            description = "현재 예약 가능한 숭실대학교 도서관 좌석을 사용자 선호도에 따라 순위화합니다. "
                    + "이 읽기 전용 도구는 실시간 좌석 현황과 library/seat-catalog.json의 정적 좌석 카탈로그를 결합합니다. "
                    + "불리언 선호도는 true면 선호, false면 회피, null/생략이면 선호 없음을 의미합니다. "
                    + "대학원생 전용 열람실은 기본적으로 제외되며, 대학원생인 경우에만 include_graduate_only=true를 설정하세요. "
                    + "prepare_reserve_library_seat는 사용자가 추천된 좌석을 선택한 뒤에만 호출하세요. "
                    + "mcp_session_id 필요(LIBRARY 로그인)."
    )
    public McpPrivateToolResponse<LibrarySeatRecommendationResponse> recommendLibrarySeats(
            @ToolParam(description = "Library floor code. Allowed values: 2, 5, 6.")
            int floor,
            @ToolParam(description = "Prefer a window seat when true, avoid one when false.", required = false)
            Boolean window,
            @ToolParam(description = "Prefer a seat with an outlet when true, avoid one when false.", required = false)
            Boolean outlet,
            @ToolParam(description = "Prefer a standing desk when true, avoid standing desks when false.", required = false)
            Boolean standing,
            @ToolParam(description = "Prefer an edge/corner seat when true, avoid one when false.", required = false)
            Boolean edge,
            @ToolParam(description = "Prefer a quieter seat when true, avoid one when false.", required = false)
            Boolean quiet,
            @ToolParam(description = "Prefer a seat near the entrance when true, avoid one when false.", required = false)
            Boolean near_entrance,
            @ToolParam(description = "Include graduate-only reading rooms. Default false — "
                    + "undergraduate users cannot use them.", required = false)
            Boolean include_graduate_only,
            @ToolParam(description = "Maximum recommendations to return. Default 5, max 10.", required = false)
            Integer limit,
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id
    ) {
        LibraryFloor target = LibraryFloor.fromCode(floor);
        LibrarySeatPreference preference = new LibrarySeatPreference(
                window, outlet, standing, edge, quiet, near_entrance);

        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> recommendForSession(
                        principal.sessionId(), principal.studentId(), target, preference, limit, include_graduate_only))
                .orElseGet(() -> {
                    log.debug("recommend_library_seats: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<LibrarySeatRecommendationResponse>buildAuthRequired(
                            mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<LibrarySeatRecommendationResponse> recommendForSession(
            String mcpSessionId,
            String sessionKey,
            LibraryFloor floor,
            LibrarySeatPreference preference,
            Integer limit,
            Boolean includeGraduateOnly) {
        try {
            LibrarySeatRecommendationResponse data =
                    recommendationService.recommend(floor, sessionKey, preference, limit, includeGraduateOnly);
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(), data);
        } catch (LibraryAuthRequiredException exception) {
            log.debug("recommend_library_seats: library token expired, returning AUTH_REQUIRED");
            return authHelper.<LibrarySeatRecommendationResponse>buildAuthRequired(
                    mcpSessionId, McpProviderType.LIBRARY);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("library seats", exception), exception);
        }
    }
}
