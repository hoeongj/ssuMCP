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
            description = "Returns the authenticated user's current library loans including due dates. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth. "
                    + "Returns AUTH_REQUIRED with a loginUrl if LIBRARY is not authenticated — "
                    + "show the loginUrl to the user and ask them to open it in a browser, "
                    + "then retry this call with the returned mcp_session_id."
    )
    public McpPrivateToolResponse<LibraryLoansResponse> getMyLibraryLoans(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY). If absent or LIBRARY not linked, returns AUTH_REQUIRED with a loginUrl.")
            String mcp_session_id) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> {
                    log.debug("get_my_library_loans: fetching loans");
                    LibraryLoansResponse data = loansService.getLoansForSession(sessionKey);
                    return McpPrivateToolResponse.ok(mcp_session_id, data);
                })
                .orElseGet(() -> {
                    log.debug("get_my_library_loans: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }
}
