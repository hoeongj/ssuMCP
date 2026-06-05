package com.ssuai.domain.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.service.SaintScheduleService;

/**
 * End-to-end check that the chatbot's "talk to my own MCP server over Streamable HTTP"
 * claim (ADR 0010) is actually wired: the running Spring Boot process must accept a
 * Spring AI MCP client connection on its own /mcp endpoint, expose every tool
 * currently bundled in the server, and complete a real tool round-trip without
 * the in-process bean shortcut.
 *
 * The MCP client is built manually here rather than via auto-config to avoid a
 * Spring bean-vs-Tomcat startup race: the auto-configured client would try to
 * initialize during context refresh, before Tomcat has bound the random port.
 * Manual construction in the test body lets us connect after {@code @LocalServerPort}
 * is known. The auto-config wiring itself is exercised by {@code LlmChatServiceTests}
 * via constructor injection of {@code List<McpSyncClient>}.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSelfDogfoodTests {

    @LocalServerPort
    private int serverPort;

    @Autowired
    private McpAuthService mcpAuthService;

    @MockitoBean
    private SaintScheduleService saintScheduleService;

    @Test
    void clientCanListEveryToolExposedByServer() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.ListToolsResult result = client.listTools();

            assertThat(result.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder(
                            "get_today_meal",
                            "get_meal_by_date",
                            "get_meal_weekly",
                            "get_dorm_weekly_meal",
                            "search_campus_facilities",
                            "get_academic_calendar",
                            "get_auth_status",
                            "start_auth",
                            "logout_provider",
                            "logout_all",
                            "get_library_seat_status",
                            "search_library_book",
                            "get_my_library_loans",
                            "prepare_reserve_library_seat",
                            "confirm_action",
                            "get_my_schedule",
                            "get_my_grades",
                            "get_my_chapel_info",
                            "check_graduation_requirements",
                            "get_my_scholarships",
                            "simulate_gpa",
                            "get_my_assignments",
                            "get_recent_notices",
                            "search_notices",
                            "list_notice_categories",
                            "get_notice_detail",
                            "get_active_notices",
                            "get_department_notices"
                    );
        }
    }

    @Test
    void librarySeatStatusWithoutSessionReturnsAuthRequiredOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "get_library_seat_status",
                            Map.of("floor", 2)));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"AUTH_REQUIRED\"")
                    .contains("\"loginUrl\"")
                    .contains("\"mcpSessionId\"");
        }
    }

    @Test
    void linkedLibraryClientCanCallSeatStatusOverMcp() {
        McpAuthSession session = mcpAuthService.createSession();
        mcpAuthService.linkProvider(session.id(), McpProviderType.LIBRARY, "opaque-library-key");

        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "get_library_seat_status",
                            Map.of("floor", 2, "mcp_session_id", session.id().value())));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"OK\"")
                    .contains("\"floor\"")
                    .contains("\"availableSeats\"");
        }
    }

    @Test
    void clientCanCallTodayMealOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("get_today_meal", Map.of()));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"date\"")
                    .contains("\"meals\"");
        }
    }

    @Test
    void privateToolWithoutSessionReturnsAuthRequiredOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("get_my_schedule", Map.of()));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"AUTH_REQUIRED\"")
                    .contains("\"loginUrl\"")
                    .contains("\"mcpSessionId\"");
        }
    }

    @Test
    void privateToolWithValidSessionReturnsOkOverMcp() {
        McpAuthSession session = mcpAuthService.createSession();
        mcpAuthService.linkProvider(session.id(), McpProviderType.SAINT, "20221528");
        when(saintScheduleService.fetchSchedule("20221528", null, null))
                .thenReturn(new ScheduleResponse(2022, 2025, 2, List.of()));

        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "get_my_schedule",
                            Map.of("mcp_session_id", session.id().value())));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"OK\"")
                    .contains("\"mcpSessionId\"")
                    .contains(session.id().value());
        }
    }

    @Test
    void clientCanCallLibraryBookSearchOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "search_library_book",
                            Map.of("query", "파이썬")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"total\"")
                    .contains("\"items\"")
                    .contains("\"title\"");
        }
    }

    @Test
    void clientCanCallFacilitySearchOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "search_campus_facilities",
                            Map.of("query", "카페")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text).contains("\"facilities\"");
        }
    }

    private static String extractText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(content -> content instanceof McpSchema.TextContent)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst()
                .orElseThrow();
    }

    private McpSyncClient openClient() {
        return McpClient.sync(
                        HttpClientStreamableHttpTransport.builder("http://localhost:" + serverPort)
                                .connectTimeout(Duration.ofSeconds(5))
                                .build())
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
    }
}
