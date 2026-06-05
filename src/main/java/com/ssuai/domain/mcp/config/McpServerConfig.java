package com.ssuai.domain.mcp.config;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.ssuai.domain.mcp.tool.CampusMcpTools;
import com.ssuai.domain.mcp.tool.ConfirmActionMcpTool;
import com.ssuai.domain.mcp.tool.DormMcpTools;
import com.ssuai.domain.mcp.tool.LibraryBookMcpTool;
import com.ssuai.domain.mcp.tool.LibraryLoansMcpTool;
import com.ssuai.domain.mcp.tool.LibraryReservationMcpTool;
import com.ssuai.domain.mcp.tool.LibrarySeatMcpTool;
import com.ssuai.domain.mcp.tool.LmsAssignmentsMcpTool;
import com.ssuai.domain.mcp.tool.MealMcpTools;
import com.ssuai.domain.mcp.tool.McpAuthMcpTools;
import com.ssuai.domain.mcp.tool.NoticeMcpTools;
import com.ssuai.domain.mcp.tool.SaintExtendedMcpTools;
import com.ssuai.domain.mcp.tool.SaintGradesMcpTool;
import com.ssuai.domain.mcp.tool.SaintScheduleMcpTool;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

@Configuration
class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    /**
     * Tools that write/modify state: auth session creation and logout.
     * All other tools are read-only data queries.
     */
    private static final Set<String> DESTRUCTIVE_TOOLS = Set.of("logout_provider", "logout_all");
    private static final Set<String> WRITE_TOOLS = Set.of(
            "start_auth",
            "logout_provider",
            "logout_all",
            "prepare_reserve_library_seat",
            "confirm_action");

    @Bean
    ToolCallbackProvider ssuaiMcpTools(
            MealMcpTools mealMcpTools,
            DormMcpTools dormMcpTools,
            CampusMcpTools campusMcpTools,
            McpAuthMcpTools mcpAuthMcpTools,
            LibrarySeatMcpTool libraryMcpTool,
            LibraryBookMcpTool libraryBookMcpTool,
            LibraryLoansMcpTool libraryLoansMcpTool,
            LibraryReservationMcpTool libraryReservationMcpTool,
            ConfirmActionMcpTool confirmActionMcpTool,
            SaintScheduleMcpTool saintScheduleMcpTool,
            SaintGradesMcpTool saintGradesMcpTool,
            SaintExtendedMcpTools saintExtendedMcpTools,
            LmsAssignmentsMcpTool lmsAssignmentsMcpTool,
            NoticeMcpTools noticeMcpTools
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        mealMcpTools,
                        dormMcpTools,
                        campusMcpTools,
                        mcpAuthMcpTools,
                        libraryMcpTool,
                        libraryBookMcpTool,
                        libraryLoansMcpTool,
                        libraryReservationMcpTool,
                        confirmActionMcpTool,
                        saintScheduleMcpTool,
                        saintGradesMcpTool,
                        saintExtendedMcpTools,
                        lmsAssignmentsMcpTool,
                        noticeMcpTools)
                .build();
    }

    /**
     * Adds MCP tool annotations (readOnlyHint, destructiveHint) to all registered tools
     * so that Claude and other MCP clients can group tools visually into
     * "Read-only tools" and "Write/delete tools" sections.
     *
     * <p>Also sets immediateExecution(true) which is normally handled by the auto-configured
     * servletMcpSyncServerCustomizer — this bean replaces that one as {@code @Primary}.
     *
     * <p>Annotation semantics:
     * <ul>
     *   <li>readOnlyHint=true — tool only reads data, no side effects</li>
     *   <li>destructiveHint=true — tool deletes or invalidates state (logout operations)</li>
     * </ul>
     */
    @Primary
    @Bean
    McpSyncServerCustomizer ssuaiToolAnnotationsCustomizer() {
        return spec -> {
            // WebMVC servlet mode requires immediate (non-deferred) execution.
            // This mirrors what the auto-configured servletMcpSyncServerCustomizer does.
            spec.immediateExecution(true);

            // Access the package-private `tools` list via reflection to rebuild each
            // McpSchema.Tool with the appropriate ToolAnnotations.
            try {
                Field toolsField = McpServer.SyncSpecification.class.getDeclaredField("tools");
                toolsField.setAccessible(true);

                @SuppressWarnings("unchecked")
                List<McpServerFeatures.SyncToolSpecification> tools =
                        (List<McpServerFeatures.SyncToolSpecification>) toolsField.get(spec);

                List<McpServerFeatures.SyncToolSpecification> annotated = tools.stream()
                        .map(McpServerConfig::withAnnotations)
                        .collect(Collectors.toList());

                tools.clear();
                tools.addAll(annotated);

                log.debug("Applied MCP tool annotations to {} tools", annotated.size());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                log.warn("Could not apply MCP tool annotations — tool grouping will be unavailable", e);
            }
        };
    }

    private static McpServerFeatures.SyncToolSpecification withAnnotations(
            McpServerFeatures.SyncToolSpecification original) {

        String name = original.tool().name();
        boolean readOnly = !WRITE_TOOLS.contains(name);
        boolean destructive = DESTRUCTIVE_TOOLS.contains(name);

        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(
                null,        // title — not used by Claude UI yet
                readOnly,    // readOnlyHint: true for all data-query tools
                destructive, // destructiveHint: true only for logout operations
                null,        // idempotentHint
                null,        // openWorldHint
                null         // returnDirect
        );

        McpSchema.Tool annotatedTool = McpSchema.Tool.builder()
                .name(original.tool().name())
                .description(original.tool().description())
                .inputSchema(original.tool().inputSchema())
                .annotations(annotations)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(annotatedTool)
                .callHandler(original.callHandler())
                .build();
    }
}
