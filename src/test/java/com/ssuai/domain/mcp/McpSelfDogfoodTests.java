package com.ssuai.domain.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Autowired
    private ObjectMapper objectMapper;

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
                            "find_academic_calendar_events",
                            "get_auth_status",
                            "start_auth",
                            "logout_provider",
                            "logout_all",
                            "get_library_seat_status",
                            "get_library_seat_catalog",
                            "recommend_library_seats",
                            "get_library_available_seats",
                            "get_room_available_seats",
                            "search_library_book",
                            "get_my_library_loans",
                            "prepare_reserve_library_seat",
                            "prepare_cancel_library_seat",
                            "get_my_library_seat",
                            "prepare_swap_library_seat",
                            "wait_for_library_seat",
                            "get_library_wait_status",
                            "cancel_library_wait",
                            "confirm_action",
                            "get_my_schedule",
                            "get_my_grades",
                            "get_my_chapel_info",
                            "check_graduation_requirements",
                            "get_my_scholarships",
                            "simulate_gpa",
                            "get_my_assignments",
                            "get_my_lms_terms",
                            "get_lms_dashboard",
                            "get_my_lms_courses",
                            "get_my_lms_materials",
                            "prepare_lms_material_export",
                            "confirm_lms_material_export",
                            "export_all_lms_materials",
                            "get_recent_notices",
                            "search_notices",
                            "list_notice_categories",
                            "get_notice_detail",
                            "get_active_notices",
                            "get_department_notices",
                            "classify_academic_question",
                            "search_academic_policy_sources",
                            "get_academic_policy_brief",
                            "check_scholarship_policy",
                            "list_academic_policy_sources",
                            "evaluate_graduation_with_policy"
                    );
        }
    }

    @Test
    void librarySeatStatusWithoutSessionReturnsPublicAggregateOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "get_library_seat_status",
                            Map.of("floor", 2)));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"floor\"")
                    .contains("\"availableSeats\"")
                    .doesNotContain("NO_SESSION")
                    .doesNotContain("loginUrl")
                    .doesNotContain("mcpSessionId");
        }
    }

    @Test
    void librarySeatStatusSupportsCompactPublicResponseOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "get_library_seat_status",
                            Map.of("floor", 2, "compact", true)));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"floor\"")
                    .contains("\"availableSeats\"")
                    .doesNotContain("zones");
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
    void privateToolWithoutSessionReturnsNoSessionOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("get_my_schedule", Map.of()));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = extractText(result);
            assertThat(text)
                    .contains("\"NO_SESSION\"")
                    .contains("\"loginUrl\":null")
                    .contains("\"mcpSessionId\":null");
        }
    }

    @Test
    void everyPrivateToolWithoutBoundSessionReturnsNoSessionOverMcp() {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            PRIVATE_TOOL_ARGUMENTS.forEach((toolName, validArguments) -> {
                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(toolName, validArguments));

                assertThat(result.isError()).as(toolName).isNotEqualTo(Boolean.TRUE);
                assertThat(extractText(result))
                        .as(toolName)
                        .contains("\"code\":\"NO_SESSION\"")
                        .contains("\"mcpSessionId\":null");
            });
        }
    }

    @Test
    void everyPrivateToolRejectsRandomExplicitIdEvenWhenTransportIsBound() throws Exception {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            String boundSessionId = startAndBindSession(client);

            PRIVATE_TOOL_ARGUMENTS.forEach((toolName, validArguments) -> {
                Map<String, Object> attackArguments = new LinkedHashMap<>(validArguments);
                attackArguments.put("mcp_session_id", "00000000-0000-0000-0000-000000000000");

                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(toolName, attackArguments));

                assertThat(result.isError()).as(toolName).isNotEqualTo(Boolean.TRUE);
                assertThat(extractText(result))
                        .as(toolName)
                        .contains("\"code\":\"INVALID_SESSION\"")
                        .contains("\"mcpSessionId\":null")
                        .doesNotContain(boundSessionId);
            });
        }
    }

    @Test
    void everyPrivateToolRejectsExplicitTransportMismatchWithoutDisclosingEitherSession() throws Exception {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            String boundSessionId = startAndBindSession(client);
            McpAuthSession otherSession = mcpAuthService.createSession();

            PRIVATE_TOOL_ARGUMENTS.forEach((toolName, validArguments) -> {
                Map<String, Object> mismatchArguments = new LinkedHashMap<>(validArguments);
                mismatchArguments.put("mcp_session_id", otherSession.id().value());

                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(toolName, mismatchArguments));

                assertThat(result.isError()).as(toolName).isNotEqualTo(Boolean.TRUE);
                assertThat(extractText(result))
                        .as(toolName)
                        .contains("\"code\":\"SESSION_MISMATCH\"")
                        .contains("\"mcpSessionId\":null")
                        .doesNotContain(boundSessionId)
                        .doesNotContain(otherSession.id().value());
            });
        }
    }

    @Test
    void everyPrivateToolRejectsInvalidatedExplicitSessionDespiteValidTransportBinding() throws Exception {
        try (McpSyncClient client = openClient()) {
            client.initialize();

            String boundSessionId = startAndBindSession(client);
            McpAuthSession invalidated = mcpAuthService.createSession();
            mcpAuthService.invalidateSession(invalidated.id());

            PRIVATE_TOOL_ARGUMENTS.forEach((toolName, validArguments) -> {
                Map<String, Object> invalidArguments = new LinkedHashMap<>(validArguments);
                invalidArguments.put("mcp_session_id", invalidated.id().value());

                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(toolName, invalidArguments));

                assertThat(result.isError()).as(toolName).isNotEqualTo(Boolean.TRUE);
                assertThat(extractText(result))
                        .as(toolName)
                        .contains("\"code\":\"INVALID_SESSION\"")
                        .contains("\"mcpSessionId\":null")
                        .doesNotContain(boundSessionId)
                        .doesNotContain(invalidated.id().value());
            });
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

    private String startAndBindSession(McpSyncClient client) throws Exception {
        McpSchema.CallToolResult start = client.callTool(new McpSchema.CallToolRequest(
                "start_auth", Map.of("provider", "SAINT")));
        String sessionId = objectMapper.readTree(extractText(start)).path("mcpSessionId").asText();
        assertThat(sessionId).isNotBlank();
        return sessionId;
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

    private static final Map<String, Map<String, Object>> PRIVATE_TOOL_ARGUMENTS = Map.ofEntries(
            Map.entry("recommend_library_seats", Map.of("floor", 2)),
            Map.entry("get_library_available_seats", Map.of()),
            Map.entry("get_room_available_seats", Map.of("roomId", 57)),
            Map.entry("get_my_library_loans", Map.of()),
            Map.entry("prepare_reserve_library_seat", Map.of("seat_id", "3352")),
            Map.entry("prepare_cancel_library_seat", Map.of()),
            Map.entry("get_my_library_seat", Map.of()),
            Map.entry("prepare_swap_library_seat", Map.of("new_seat_id", "3352")),
            Map.entry("wait_for_library_seat", Map.of("floor", "2F")),
            Map.entry("get_library_wait_status", Map.of()),
            Map.entry("cancel_library_wait", Map.of()),
            Map.entry("confirm_action", Map.of()),
            Map.entry("get_my_schedule", Map.of()),
            Map.entry("get_my_grades", Map.of()),
            Map.entry("get_my_chapel_info", Map.of()),
            Map.entry("check_graduation_requirements", Map.of()),
            Map.entry("get_my_scholarships", Map.of()),
            Map.entry("simulate_gpa", Map.of("plannedCredits", 3.0)),
            Map.entry("get_my_assignments", Map.of()),
            Map.entry("get_my_lms_terms", Map.of()),
            Map.entry("get_lms_dashboard", Map.of()),
            Map.entry("get_my_lms_courses", Map.of()),
            Map.entry("get_my_lms_materials", Map.of("course_ids", List.of(1L))),
            Map.entry("prepare_lms_material_export", Map.of("content_ids", List.of("fixture-content"))),
            Map.entry("confirm_lms_material_export", Map.of()),
            Map.entry("export_all_lms_materials", Map.of()),
            Map.entry("evaluate_graduation_with_policy", Map.of()));
}
