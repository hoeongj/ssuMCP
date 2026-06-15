package com.ssuai.domain.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class McpServerConfigTests {

    private final ToolCallbackProvider toolCallbackProvider;

    McpServerConfigTests(@Qualifier("ssuaiMcpTools") ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @Test
    void registersSsuaiMcpTools() {
        assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList())
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
                        "evaluate_graduation_with_policy");
    }
}
