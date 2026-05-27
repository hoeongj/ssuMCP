package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.TermSchedule;
import com.ssuai.domain.saint.service.SaintScheduleService;

class SaintScheduleMcpToolTests {

    private McpAuthHelper authHelper;
    private SaintScheduleService scheduleService;
    private SaintScheduleMcpTool tool;

    private static final String SESSION_ID = "test-session";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        scheduleService = mock(SaintScheduleService.class);
        tool = new SaintScheduleMcpTool(scheduleService, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<ScheduleResponse> stub =
                McpPrivateToolResponse.authRequired(null, "SAINT", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.SAINT)).thenReturn(Optional.empty());
        when(authHelper.<ScheduleResponse>buildAuthRequired(null, McpProviderType.SAINT)).thenReturn(stub);

        McpPrivateToolResponse<ScheduleResponse> resp = tool.getMySchedule(null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(resp.loginUrl()).isEqualTo("https://login.url");
        assertThat(resp.data()).isNull();
        verify(scheduleService, never()).fetchSchedule(any());
    }

    @Test
    void returnsAuthRequiredWhenSaintNotLinked() {
        McpPrivateToolResponse<ScheduleResponse> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "SAINT", "https://login.url", EXPIRES);
        when(authHelper.principalKey(SESSION_ID, McpProviderType.SAINT)).thenReturn(Optional.empty());
        when(authHelper.<ScheduleResponse>buildAuthRequired(SESSION_ID, McpProviderType.SAINT)).thenReturn(stub);

        McpPrivateToolResponse<ScheduleResponse> resp = tool.getMySchedule(SESSION_ID);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(scheduleService, never()).fetchSchedule(any());
    }

    @Test
    void returnsOkWithDataWhenLinked() {
        ScheduleResponse stub = new ScheduleResponse(2022, 2025, 2, List.of(
                new TermSchedule(2025, 2, List.of())));
        when(authHelper.principalKey(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of("20221528"));
        when(scheduleService.fetchSchedule("20221528")).thenReturn(stub);

        McpPrivateToolResponse<ScheduleResponse> resp = tool.getMySchedule(SESSION_ID);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isSameAs(stub);
        assertThat(resp.loginUrl()).isNull();
    }

    @Test
    void responseDoesNotContainStudentId() {
        ScheduleResponse stub = new ScheduleResponse(2022, 2025, 2, List.of());
        when(authHelper.principalKey(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of("20221528"));
        when(scheduleService.fetchSchedule("20221528")).thenReturn(stub);

        McpPrivateToolResponse<ScheduleResponse> resp = tool.getMySchedule(SESSION_ID);

        assertThat(resp.toString()).doesNotContain("20221528");
        assertThat(resp.provider()).isNull();
    }
}
