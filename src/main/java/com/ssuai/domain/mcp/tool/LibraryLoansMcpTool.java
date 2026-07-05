package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.domain.library.service.LibraryLoansService;
import com.ssuai.global.exception.LibraryAuthRequiredException;

/**
 * MCP tool for the authenticated user's library loans (Task 18 Slice C).
 *
 * <p>Requires mcp_session_id with the LIBRARY provider linked via start_auth and
 * the frontend library login page. The principalKey stored in McpAuthSession for
 * LIBRARY is the opaque session key that LibraryLoansService uses to look up the
 * Pyxis token — no student id is involved.
 *
 * <p>The chatbot path (LlmChatService) calls LibraryLoansService directly and does
 * not invoke this method.
 */
@Component
public class LibraryLoansMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LibraryLoansMcpTool.class);

    private final LibraryLoansService loansService;
    private final McpAuthHelper authHelper;

    public LibraryLoansMcpTool(LibraryLoansService loansService, McpAuthHelper authHelper) {
        this.loansService = loansService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_library_loans",
            description = "인증된 사용자의 현재 도서관 대출 현황을 반납 예정일과 함께 조회합니다. "
                    + "mcp_session_id 필요(LIBRARY 로그인). "
                    + "미인증 시 loginUrl이 포함된 AUTH_REQUIRED를 반환하므로, loginUrl을 사용자에게 보여주고 "
                    + "브라우저에서 로그인하도록 안내한 뒤 발급된 mcp_session_id로 다시 호출하세요."
    )
    public McpPrivateToolResponse<LibraryLoansResponse> getMyLibraryLoans(
            @ToolParam(description = "start_auth(LIBRARY)로 발급받은 MCP session ID. 없거나 LIBRARY 미연동이면 loginUrl과 함께 AUTH_REQUIRED를 반환.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> {
                    log.debug("get_my_library_loans: fetching loans");
                    try {
                        LibraryLoansResponse data = loansService.getLoansForSession(principal.studentId());
                        return McpPrivateToolResponse.<LibraryLoansResponse>ok(
                                principal.sessionId(), McpProviderType.LIBRARY.name(), data);
                    } catch (LibraryAuthRequiredException exception) {
                        log.debug("get_my_library_loans: library token expired, returning AUTH_REQUIRED");
                        return authHelper.<LibraryLoansResponse>buildAuthRequired(
                                mcp_session_id, McpProviderType.LIBRARY);
                    }
                })
                .orElseGet(() -> {
                    log.debug("get_my_library_loans: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<LibraryLoansResponse>buildAuthRequired(
                            mcp_session_id, McpProviderType.LIBRARY);
                });
    }
}
