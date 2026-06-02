package com.ssuai.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.chat.config.ChatMemoryProperties;
import com.ssuai.domain.chat.config.LlmChatProperties;
import com.ssuai.domain.chat.dto.ChatResponse;
import com.ssuai.domain.chat.dto.OpenAiChatCompletionRequest;
import com.ssuai.domain.chat.dto.OpenAiChatCompletionResponse;
import com.ssuai.domain.chat.dto.OpenAiToolCall;
import com.ssuai.domain.chat.memory.ChatConversationStore;
import com.ssuai.domain.chat.service.llm.LlmCompletionRequest;
import com.ssuai.domain.chat.service.llm.LlmCompletionResult;
import com.ssuai.domain.chat.service.llm.LlmPrivacyMode;
import com.ssuai.domain.chat.service.llm.LlmProvider;
import com.ssuai.domain.chat.service.llm.LlmProviderException;

class LlmChatServiceTests {

    private static final String FACILITY_JSON_LIBRARY_CAFE = """
            {"facilities":[{
                "id":"library-soongsil-maru",
                "name":"도서관 커피점",
                "category":"CAFE",
                "categoryLabel":"카페",
                "location":"도서관 6층",
                "fax":null,
                "weekdayHours":["11:00~17:30"],
                "weekendHours":["휴무"],
                "notes":["공개 운영시간만 제공"],
                "aliases":["숭실마루","도서관 카페"]
            }]}
            """;

    private static final String FACILITY_JSON_EMPTY = "{\"facilities\":[]}";

    private final McpSyncClient mcpClient = mock(McpSyncClient.class);
    private final com.ssuai.domain.saint.service.SaintScheduleService scheduleService =
            mock(com.ssuai.domain.saint.service.SaintScheduleService.class);
    private final com.ssuai.domain.saint.service.SaintGradesService gradesService =
            mock(com.ssuai.domain.saint.service.SaintGradesService.class);
    private final com.ssuai.domain.saint.service.SaintChapelService chapelService =
            mock(com.ssuai.domain.saint.service.SaintChapelService.class);
    private final com.ssuai.domain.saint.service.SaintGraduationService graduationService =
            mock(com.ssuai.domain.saint.service.SaintGraduationService.class);
    private final com.ssuai.domain.saint.service.SaintScholarshipService scholarshipService =
            mock(com.ssuai.domain.saint.service.SaintScholarshipService.class);
    private final com.ssuai.domain.lms.service.LmsAssignmentsService lmsAssignmentsService =
            mock(com.ssuai.domain.lms.service.LmsAssignmentsService.class);
    private final com.ssuai.domain.library.service.LibrarySeatService librarySeatService =
            mock(com.ssuai.domain.library.service.LibrarySeatService.class);
    private final com.ssuai.domain.library.service.LibraryLoansService libraryLoansService =
            mock(com.ssuai.domain.library.service.LibraryLoansService.class);

    @Test
    void fallsBackAcrossProvidersWhenFirstProviderRateLimitIsExceeded() {
        FakeProvider gemini = new FakeProvider("gemini")
                .fail(new LlmProviderException("gemini", "quota", 429, "rate limit", true, null));
        FakeProvider groq = new FakeProvider("groq")
                .reply("groq-model", "fallback reply");
        LlmChatService chatService = chatService(List.of(gemini, groq), List.of("gemini", "groq"));

        ChatResponse response = chatService.reply("c-test", "오늘 학식 뭐야?");

        assertThat(response.reply()).isEqualTo("fallback reply");
        assertThat(gemini.callCount()).isEqualTo(1);
        assertThat(groq.callCount()).isEqualTo(1);
    }

    @Test
    void followsConfiguredProviderOrder() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "gemini reply");
        FakeProvider groq = new FakeProvider("groq")
                .reply("groq-model", "groq reply");
        LlmChatService chatService = chatService(List.of(gemini, groq), List.of("groq", "gemini"));

        ChatResponse response = chatService.reply("c-test", "오늘 학식 뭐야?");

        assertThat(response.reply()).isEqualTo("groq reply");
        assertThat(gemini.callCount()).isZero();
        assertThat(groq.callCount()).isEqualTo(1);
    }

    @Test
    void publicRequestFallsBackToPrivateProviderPoolWhenPublicProvidersAreExhausted() {
        FakeProvider publicProvider = new FakeProvider("public-provider")
                .fail(new LlmProviderException("public-provider", "quota", 429, "rate limit", true, null));
        FakeProvider privateProvider = new FakeProvider("private-provider")
                .reply("private-model", "private fallback reply");
        LlmChatService chatService = chatService(
                List.of(publicProvider, privateProvider),
                List.of("public-provider"),
                List.of("private-provider"),
                0
        );

        ChatResponse response = chatService.reply("c-test", "오늘 학식 뭐야?");

        assertThat(response.reply()).isEqualTo("private fallback reply");
        assertThat(publicProvider.callCount()).isEqualTo(1);
        assertThat(privateProvider.callCount()).isEqualTo(1);
        assertThat(privateProvider.lastPrivacyMode()).isEqualTo(LlmPrivacyMode.PRIVATE);
    }

    @Test
    void verificationPassRetriesProviderOrderFromTheBeginning() {
        FakeProvider gemini = new FakeProvider("gemini")
                .fail(new LlmProviderException("gemini", "quota", 429, "rate limit", true, null))
                .reply("gemini-model", "recovered reply");
        FakeProvider groq = new FakeProvider("groq")
                .fail(new LlmProviderException("groq", "quota", 429, "rate limit", true, null));
        LlmChatService chatService = chatService(
                List.of(gemini, groq),
                List.of("gemini", "groq"),
                List.of("missing-private-provider"),
                1
        );

        ChatResponse response = chatService.reply("c-test", "오늘 학식 뭐야?");

        assertThat(response.reply()).isEqualTo("recovered reply");
        assertThat(gemini.callCount()).isEqualTo(2);
        assertThat(groq.callCount()).isEqualTo(1);
    }

    @Test
    void skipsProvidersWithoutConfiguredApiKeys() {
        FakeProvider unconfigured = new FakeProvider("missing-key")
                .unconfigured();
        FakeProvider configured = new FakeProvider("configured")
                .reply("configured-model", "configured reply");
        LlmChatService chatService = chatService(
                List.of(unconfigured, configured),
                List.of("missing-key", "configured"),
                List.of(),
                0
        );

        ChatResponse response = chatService.reply("c-test", "오늘 학식 뭐야?");

        assertThat(response.reply()).isEqualTo("configured reply");
        assertThat(unconfigured.callCount()).isZero();
        assertThat(configured.callCount()).isEqualTo(1);
    }

    @Test
    void limitsConfiguredProviderAttempts() {
        FakeProvider first = new FakeProvider("first")
                .fail(new LlmProviderException("first", "quota", 429, "rate limit", true, null));
        FakeProvider second = new FakeProvider("second")
                .fail(new LlmProviderException("second", "quota", 429, "rate limit", true, null));
        FakeProvider third = new FakeProvider("third")
                .reply("third-model", "should not be called");
        LlmChatProperties properties = properties(List.of("first", "second", "third"), List.of(), 0);
        properties.setMaxProviderAttempts(2);
        LlmChatService chatService = chatService(List.of(first, second, third), properties);

        try {
            chatService.reply("c-test", "오늘 학식 뭐야?");
        } catch (RuntimeException ignored) {
            // Expected: the first two configured providers are exhausted.
        }

        assertThat(first.callCount()).isEqualTo(1);
        assertThat(second.callCount()).isEqualTo(1);
        assertThat(third.callCount()).isZero();
    }

    @Test
    void facilityToolResultIsCompactedBeforeFinalCompletion() {
        when(mcpClient.callTool(argThat(named("search_campus_facilities"))))
                .thenReturn(toolTextResult(FACILITY_JSON_LIBRARY_CAFE));
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("search_campus_facilities", "{\"query\":\"카페\"}")
                ))
                .reply("gemini-model", "도서관 커피점은 도서관 6층에 있어요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "카페 어디 있어?");

        assertThat(response.reply()).isEqualTo("도서관 커피점은 도서관 6층에 있어요.");
        assertThat(provider.callCount()).isEqualTo(2);
        verify(mcpClient, times(1))
                .callTool(argThat(request ->
                        "search_campus_facilities".equals(request.name())
                                && "카페".equals(request.arguments().get("query"))));
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent)
                .contains("\"resultCount\":1")
                .contains("\"name\":\"도서관 커피점\"")
                .doesNotContain("aliases")
                .doesNotContain("fax")
                .doesNotContain("\"id\":");
    }

    @Test
    void facilityToolRequiresQueryBeforeCallingMcp() {
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("search_campus_facilities", "{}")
                ))
                .reply("gemini-model", "시설 종류를 한 단어로 물어봐 주세요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "캠퍼스 시설 알려줘");

        assertThat(response.reply()).isEqualTo("시설 종류를 한 단어로 물어봐 주세요.");
        verify(mcpClient, never()).callTool(any());
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent).contains("검색어");
    }

    @Test
    void limitsExecutedToolCallsBeforeFinalCompletion() {
        when(mcpClient.callTool(argThat(named("search_campus_facilities"))))
                .thenReturn(toolTextResult(FACILITY_JSON_EMPTY));
        FakeProvider provider = new FakeProvider("gemini")
                .toolCalls("gemini-model", List.of(
                        new OpenAiToolCall(
                                "call-1",
                                "function",
                                new OpenAiToolCall.FunctionCall("search_campus_facilities", "{\"query\":\"카페\"}")
                        ),
                        new OpenAiToolCall(
                                "call-2",
                                "function",
                                new OpenAiToolCall.FunctionCall("get_dorm_weekly_meal", "{}")
                        )
                ))
                .reply("gemini-model", "카페 검색 결과만 먼저 확인했어요.");
        LlmChatProperties properties = properties(List.of("gemini"), List.of(), 0);
        properties.setMaxToolCalls(1);
        LlmChatService chatService = chatService(List.of(provider), properties);

        ChatResponse response = chatService.reply("c-test", "카페랑 기숙사 식단 알려줘");

        assertThat(response.reply()).isEqualTo("카페 검색 결과만 먼저 확인했어요.");
        verify(mcpClient, never())
                .callTool(argThat(named("get_dorm_weekly_meal")));
        verify(mcpClient, times(1)).callTool(any());
        List<String> toolContents = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .map(OpenAiChatCompletionRequest.Message::content)
                .toList();
        assertThat(toolContents).hasSize(2);
        assertThat(toolContents.get(0)).contains("\"resultCount\":0");
        assertThat(toolContents.get(1)).contains("도구 호출 수를 초과");
    }

    @Test
    void outOfScopeRequestReturnsScopeGuidanceWithoutCallingProviders() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "should not be called");
        LlmChatService chatService = chatService(List.of(gemini), List.of("gemini"));

        ChatResponse response = chatService.reply("c-test", "수강신청 어떻게 해?");

        assertThat(response.reply()).contains("그 정보는 지원하지 않");
        assertThat(gemini.callCount()).isZero();
        verify(mcpClient, never()).callTool(any());
    }

    @Test
    void graduationRequirementsRequestIsNotRejectedAsOutOfScope() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "졸업 요건을 확인해드릴게요.");
        LlmChatService chatService = chatService(List.of(gemini), List.of("gemini"));

        ChatResponse response = chatService.reply("c-test", "졸업요건 알려줘");

        assertThat(response.reply()).isEqualTo("졸업 요건을 확인해드릴게요.");
        assertThat(gemini.callCount()).isEqualTo(1);
    }

    @Test
    void authenticationToolsAreNotExposedToChatProviders() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "ok");
        LlmChatService chatService = chatService(List.of(gemini), List.of("gemini"));

        chatService.reply("c-test", "오늘 학식 뭐야?");

        List<String> toolNames = gemini.request(0).tools().stream()
                .map(tool -> tool.function().name())
                .toList();
        assertThat(toolNames)
                .contains("get_today_meal")
                .doesNotContain("start_auth", "get_auth_status", "logout_provider", "logout_all");
    }

    @Test
    void skipsFailedSecondaryMcpClientDuringToolDiscovery() {
        McpSyncClient brokenClient = mock(McpSyncClient.class);
        doReturn(true).when(mcpClient).isInitialized();
        doReturn(canonicalListToolsResult()).when(mcpClient).listTools();
        doReturn(true).when(brokenClient).isInitialized();
        when(brokenClient.listTools()).thenThrow(new IllegalStateException("missing tavily url"));
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "ok");
        LlmChatService chatService = chatService(
                List.of(gemini),
                properties(List.of("gemini"), List.of(), 0),
                List.of(mcpClient, brokenClient)
        );

        ChatResponse response = chatService.reply("c-test", "오늘 학식 뭐야?");

        assertThat(response.reply()).isEqualTo("ok");
        assertThat(gemini.request(0).tools()).extracting(tool -> tool.function().name())
                .contains("get_today_meal")
                .doesNotContain("start_auth");
    }

    @Test
    void unknownToolCallIsForwardedToOwningSecondaryMcpClient() {
        McpSyncClient tavilyClient = mock(McpSyncClient.class);
        doReturn(true).when(mcpClient).isInitialized();
        doReturn(canonicalListToolsResult()).when(mcpClient).listTools();
        doReturn(true).when(tavilyClient).isInitialized();
        doReturn(new McpSchema.ListToolsResult(
                List.of(canonicalTool("tavily_search",
                        "인터넷 실시간 검색을 수행합니다.",
                        requiredStringSchema("query", "검색어"))),
                null
        )).when(tavilyClient).listTools();
        when(tavilyClient.callTool(argThat(named("tavily_search"))))
                .thenReturn(toolTextResult("{\"results\":[{\"title\":\"숭실대 컴퓨터학부\",\"url\":\"https://example.test\"}]}"));
        FakeProvider gemini = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("tavily_search",
                                "{\"query\":\"숭실대 컴퓨터학부 교수님 이름\"}")
                ))
                .reply("gemini-model", "검색 결과 기준으로 답했어요.");
        LlmChatService chatService = chatService(
                List.of(gemini),
                properties(List.of("gemini"), List.of(), 0),
                List.of(mcpClient, tavilyClient)
        );

        ChatResponse response = chatService.reply("c-test", "숭실대 컴퓨터학부 교수님 이름 알려줘");

        assertThat(response.reply()).isEqualTo("검색 결과 기준으로 답했어요.");
        assertThat(gemini.request(0).tools()).extracting(tool -> tool.function().name())
                .contains("get_today_meal", "tavily_search");
        verify(tavilyClient, times(1))
                .callTool(argThat(request ->
                        "tavily_search".equals(request.name())
                                && "숭실대 컴퓨터학부 교수님 이름".equals(request.arguments().get("query"))));
        verify(mcpClient, never()).callTool(argThat(named("tavily_search")));
    }

    @Test
    void secretLikeInputReturnsGuidanceWithoutCallingProviders() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "should not be called");
        LlmChatService chatService = chatService(List.of(gemini), List.of("gemini"));

        ChatResponse response = chatService.reply("c-test", "내 비밀번호는 1234야");

        assertThat(response.reply()).contains("비밀번호");
        assertThat(gemini.callCount()).isZero();
        verify(mcpClient, never()).callTool(any());
    }

    @Test
    void secondTurnRequestIncludesPriorUserAndAssistantHistory() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "학생식당과 숭실도담식당 중 어디가 궁금해?")
                .reply("gemini-model", "숭실도담식당은 오늘 후라이드치킨이 나와요.");
        LlmChatService chatService = chatService(List.of(gemini), List.of("gemini"));

        chatService.reply("c-multi", "오늘 학식 뭐야?");
        ChatResponse second = chatService.reply("c-multi", "도담");

        assertThat(second.reply()).isEqualTo("숭실도담식당은 오늘 후라이드치킨이 나와요.");
        assertThat(gemini.callCount()).isEqualTo(2);

        List<OpenAiChatCompletionRequest.Message> secondRequestMessages = gemini.request(1).messages();
        assertThat(secondRequestMessages).extracting(OpenAiChatCompletionRequest.Message::role)
                .containsExactly("system", "system", "system", "user", "assistant", "user");
        assertThat(secondRequestMessages.get(3).content()).isEqualTo("오늘 학식 뭐야?");
        assertThat(secondRequestMessages.get(4).content()).isEqualTo("학생식당과 숭실도담식당 중 어디가 궁금해?");
        assertThat(secondRequestMessages.get(5).content()).isEqualTo("도담");
    }

    @Test
    void historyIsScopedPerConversationId() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "first conv reply")
                .reply("gemini-model", "second conv reply");
        LlmChatService chatService = chatService(List.of(gemini), List.of("gemini"));

        chatService.reply("c-one", "첫 대화");
        chatService.reply("c-two", "다른 대화");

        List<OpenAiChatCompletionRequest.Message> secondRequestMessages = gemini.request(1).messages();
        assertThat(secondRequestMessages).extracting(OpenAiChatCompletionRequest.Message::role)
                .containsExactly("system", "system", "system", "user");
        assertThat(secondRequestMessages.get(3).content()).isEqualTo("다른 대화");
    }

    @Test
    void injectsCurrentKstDateAsSecondSystemMessage() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "어제는 학생식당 메뉴를 보여줄게요.");
        // 2026-03-05T03:00Z = 2026-03-05T12:00 KST (Thursday).
        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-05T03:00:00Z"), ZoneOffset.UTC);
        LlmChatService chatService = chatServiceWithClock(List.of(gemini), List.of("gemini"), fixedClock);

        chatService.reply("c-date", "어제 학식 뭐야?");

        List<OpenAiChatCompletionRequest.Message> request = gemini.request(0).messages();
        assertThat(request).extracting(OpenAiChatCompletionRequest.Message::role)
                .containsExactly("system", "system", "system", "user");
        assertThat(request.get(0).content()).contains("ssuAI 챗봇");
        assertThat(request.get(1).content())
                .contains("2026-03-05")
                .contains("(목)")
                .contains("Asia/Seoul")
                .contains("yyyy-MM-dd");
        assertThat(request.get(2).content())
                .contains("비인증")
                .contains("start_auth 같은 도구는 이 챗봇에 없어");
    }

    @Test
    void buildTodayContextMessageHonorsKstZoneAcrossUtcDayBoundary() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "ok");
        // 2026-03-05T20:00Z = 2026-03-06T05:00 KST (Friday).
        Clock lateUtcEarlyKst = Clock.fixed(Instant.parse("2026-03-05T20:00:00Z"), ZoneOffset.UTC);
        LlmChatService chatService = chatServiceWithClock(List.of(gemini), List.of("gemini"), lateUtcEarlyKst);

        String dateMessage = chatService.buildTodayContextMessage();

        assertThat(dateMessage).contains("2026-03-06").contains("(금)");
    }

    @Test
    void authContextMessageDirectsAuthenticatedSaintToolUseWithoutAuthToolHallucination() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("gemini").reply("gemini-model", "ok")),
                List.of("gemini"));

        String authMessage = chatService.buildAuthContextMessage("20221528");

        assertThat(authMessage)
                .contains("인증됨")
                .contains("get_my_grades")
                .contains("start_auth 같은 도구는 이 챗봇에 없어")
                .contains("절대 언급하거나 호출하지 마");
    }

    @Test
    void authContextMessageDirectsAnonymousUserToDashboardLogin() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("gemini").reply("gemini-model", "ok")),
                List.of("gemini"));

        assertThat(chatService.buildAuthContextMessage(null))
                .contains("비인증")
                .contains("대시보드에서 SmartID로 로그인하면 볼 수 있어요")
                .contains("start_auth 같은 도구는 이 챗봇에 없어");
        assertThat(chatService.buildAuthContextMessage("")).contains("비인증");
    }

    @Test
    void secretLikeInputDoesNotPollutateHistory() {
        FakeProvider gemini = new FakeProvider("gemini")
                .reply("gemini-model", "직전 메시지에 비밀번호가 있었더라도 잊고 학식만 안내할게.");
        LlmChatService chatService = chatService(List.of(gemini), List.of("gemini"));

        chatService.reply("c-secret", "내 비밀번호는 1234야");
        ChatResponse second = chatService.reply("c-secret", "오늘 학식 뭐야?");

        assertThat(second.reply()).isEqualTo("직전 메시지에 비밀번호가 있었더라도 잊고 학식만 안내할게.");
        List<OpenAiChatCompletionRequest.Message> secondRequestMessages = gemini.request(0).messages();
        assertThat(secondRequestMessages).extracting(OpenAiChatCompletionRequest.Message::content)
                .doesNotContain("내 비밀번호는 1234야");
        assertThat(secondRequestMessages).extracting(OpenAiChatCompletionRequest.Message::content)
                .doesNotContain("비밀번호");
    }

    @Test
    void privateScheduleToolCallBypassesMcpAndUsesAuthenticatedStudentId() {
        com.ssuai.domain.saint.dto.ScheduleResponse stub = new com.ssuai.domain.saint.dto.ScheduleResponse(
                2022, 2025, 2,
                List.of(new com.ssuai.domain.saint.dto.TermSchedule(2025, 2, List.of(
                        new com.ssuai.domain.saint.dto.ScheduleEntry(
                                1, "월", 1, "09:00-09:50", "운영체제", "김교수", "정보과학관 401")))));
        when(scheduleService.fetchSchedule("20221528")).thenReturn(stub);
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_my_schedule", "{}")))
                .reply("gemini-model", "이번 학기 월 1교시는 운영체제예요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "내 시간표 알려줘", "20221528");

        assertThat(response.reply()).contains("운영체제");
        verify(scheduleService).fetchSchedule("20221528");
        verify(mcpClient, never()).callTool(argThat(named("get_my_schedule")));
        assertThat(provider.request(0).privacyMode()).isEqualTo(LlmPrivacyMode.PRIVATE);
        assertThat(provider.request(1).privacyMode()).isEqualTo(LlmPrivacyMode.PRIVATE);
        // 두 번째 LLM call (tool 결과 포함) — professor / timeRange / dayLabel 차단 확인
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent)
                .contains("\"course\":\"운영체제\"")
                .doesNotContain("professor")
                .doesNotContain("김교수")
                .doesNotContain("timeRange")
                .doesNotContain("09:00-09:50")
                .doesNotContain("dayLabel");
    }

    @Test
    void privateScheduleToolReturnsLoginGuidanceForAnonymousChat() {
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_my_schedule", "{}")))
                .reply("gemini-model", "u-SAINT 로그인이 필요해요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "내 시간표 알려줘", null);

        assertThat(response.reply()).contains("로그인");
        verify(scheduleService, never()).fetchSchedule(org.mockito.ArgumentMatchers.any());
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent).contains("로그인");
    }

    @Test
    void conversationThatUsesPrivateToolKeepsLaterHistoryOnPrivateProviders() {
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_my_schedule", "{}")))
                .reply("gemini-model", "u-SAINT 로그인이 필요해요.")
                .reply("gemini-model", "학생식당 메뉴를 확인할게요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        chatService.reply("c-private-history", "내 시간표 알려줘", null);
        chatService.reply("c-private-history", "그럼 오늘 학식 알려줘", null);

        assertThat(provider.request(0).privacyMode()).isEqualTo(LlmPrivacyMode.PUBLIC);
        assertThat(provider.request(1).privacyMode()).isEqualTo(LlmPrivacyMode.PRIVATE);
        assertThat(provider.request(2).privacyMode()).isEqualTo(LlmPrivacyMode.PRIVATE);
    }

    @Test
    void privateChapelToolReturnsLoginGuidanceForAnonymousChat() {
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_my_chapel_info", "{}")))
                .reply("gemini-model", "u-SAINT 로그인이 필요해요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "내 채플 출석 알려줘", null);

        assertThat(response.reply()).contains("로그인");
        verify(chapelService, never()).fetchChapelInfo(any(), any(), any());
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent).contains("로그인");
    }

    @Test
    void privateLibraryToolReturnsCredentialLoginGuidanceWhenLibrarySessionMissing() {
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_library_seat_status", "{\"floor\":2}")))
                .reply("gemini-model", "도서관 세션 연동이 필요해요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "도서관 자리 있어?", null);

        assertThat(response.reply()).contains("도서관 세션");
        verify(librarySeatService, never()).getSeatStatusForSession(any(), any());
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent)
                .contains("학번과 비밀번호")
                .contains("도서관 연동")
                .doesNotContain("Pyxis-Auth-Token")
                .doesNotContain("붙여넣");
    }

    @Test
    void noticeSearchToolRequiresKeywordBeforeCallingMcp() {
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("search_notices", "{}")))
                .reply("gemini-model", "검색어를 입력해 주세요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "공지 검색해줘");

        assertThat(response.reply()).contains("검색어");
        verify(mcpClient, never()).callTool(argThat(named("search_notices")));
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent).contains("검색어를 입력해 주세요.");
    }

    @Test
    void privateGradesToolCompactsToCountAndLinkOnly() {
        com.ssuai.domain.saint.dto.GpaSummary summary =
                new com.ssuai.domain.saint.dto.GpaSummary(18.0, 18.0, 63.00, 3.50, 85.00, 3.0);
        com.ssuai.domain.saint.dto.GradesResponse stub =
                new com.ssuai.domain.saint.dto.GradesResponse(
                        List.of(new com.ssuai.domain.saint.dto.TermGpa(
                                2025, "2학기", 18.0, 18.0, 3.0, 3.50, 63.00, 85.00,
                                "50/100", "60/100", false, false, false)),
                        summary,
                        summary,
                        Map.of("2025-2학기", List.of(
                                new com.ssuai.domain.saint.dto.CourseGrade(
                                        "95", "A0", "운영체제", "21500001", 3.0, "김교수", ""))));
        when(gradesService.fetchGrades("20221528")).thenReturn(stub);
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_my_grades", "{}")))
                .reply("gemini-model", "성적 페이지에서 1과목 확인 가능합니다.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "내 성적 알려줘", "20221528");

        assertThat(response.reply()).contains("성적 페이지");
        verify(gradesService).fetchGrades("20221528");
        // tool content 가 LLM 으로 가는데, raw GPA/과목명/점수/등급/교수명 절대 X
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent)
                .contains("\"count\":1")
                .contains("\"link\":\"/grades\"")
                .contains("\"academicRecord\"")
                .contains("\"history\"")
                // per-course detail (scores, grades, names) never reaches LLM
                .doesNotContain("95")
                .doesNotContain("A0")
                .doesNotContain("운영체제")
                .doesNotContain("김교수")
                .doesNotContain("21500001")
                .doesNotContain("detailsByTerm");
    }

    @Test
    void privateGradesAuditLogPinsToolNameAndStudentFingerprintWithoutLeakingPayload() {
        com.ssuai.domain.saint.dto.GpaSummary summary =
                new com.ssuai.domain.saint.dto.GpaSummary(18.0, 18.0, 63.00, 3.50, 85.00, 3.0);
        com.ssuai.domain.saint.dto.GradesResponse stub =
                new com.ssuai.domain.saint.dto.GradesResponse(
                        List.of(new com.ssuai.domain.saint.dto.TermGpa(
                                2025, "2학기", 18.0, 18.0, 3.0, 3.50, 63.00, 85.00,
                                "50/100", "60/100", false, false, false)),
                        summary,
                        summary,
                        Map.of("2025-2학기", List.of(
                                new com.ssuai.domain.saint.dto.CourseGrade(
                                        "95", "A0", "운영체제", "21500001", 3.0, "김교수", ""))));
        when(gradesService.fetchGrades("20221528")).thenReturn(stub);
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_my_grades", "{}")))
                .reply("gemini-model", "성적 페이지에서 1과목 확인 가능합니다.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(LlmChatService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            chatService.reply("c-test", "내 성적 알려줘", "20221528");

            String expectedFp = com.ssuai.domain.auth.saint.SaintSessionStore.fingerprint("20221528");
            List<String> messages = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();

            // 의도 (requested) + 완료 (completed) 둘 다 도구명 + fingerprint 만 포함
            assertThat(messages)
                    .anyMatch(m -> m.contains("chat private tool requested")
                            && m.contains("get_my_grades")
                            && m.contains(expectedFp))
                    .anyMatch(m -> m.contains("chat private tool completed")
                            && m.contains("get_my_grades")
                            && m.contains(expectedFp));

            // 모든 log line — raw 성적 데이터 차단 (점수/등급/과목명/학수번호/교수명/GPA/석차)
            for (String m : messages) {
                assertThat(m)
                        .doesNotContain("3.50")
                        .doesNotContain("262.50")
                        .doesNotContain("85.00")
                        .doesNotContain("18.0")
                        .doesNotContain("95")
                        .doesNotContain("A0")
                        .doesNotContain("운영체제")
                        .doesNotContain("21500001")
                        .doesNotContain("김교수")
                        .doesNotContain("50/100")
                        .doesNotContain("20221528");
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void privateGradesToolReturnsExpiredSessionGuidanceWhenSessionStoreLapsed() {
        when(gradesService.fetchGrades("20221528"))
                .thenThrow(new com.ssuai.global.exception.SaintSessionExpiredException());
        FakeProvider provider = new FakeProvider("gemini")
                .toolCall("gemini-model", new OpenAiToolCall(
                        "call-1",
                        "function",
                        new OpenAiToolCall.FunctionCall("get_my_grades", "{}")))
                .reply("gemini-model", "u-SAINT 세션이 만료됐어요.");
        LlmChatService chatService = chatService(List.of(provider), List.of("gemini"), List.of(), 0);

        ChatResponse response = chatService.reply("c-test", "내 성적 알려줘", "20221528");

        assertThat(response.reply()).contains("만료");
        String toolContent = provider.request(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(toolContent).contains("만료");
    }

    /**
     * Pins Task 16 spec §6 #6 — grades NEVER cross into LLM prompts. The
     * upstream {@code GradesResponse} JSON carries per-term GPA, course
     * names, scores, grade letters, professor names, rank fractions —
     * none of those may leak through the compact step. Only a course
     * count + a deep link reach the LLM.
     */
    @Test
    void gradesCompactExposesGpaAndHistoryButBlocksPerCourseDetail() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("noop").reply("noop", "noop")),
                List.of("noop"));
        String rawGradesJson = """
                {
                  "history":[
                    {"year":2025,"term":"2학기","requestedCredits":18.0,"earnedCredits":18.0,
                     "passFailCredits":3.0,"gpa":3.50,"gpaSum":63.00,"arithmeticAverage":85.00,
                     "rankInTerm":"50/100","rankOverall":"60/100",
                     "academicWarning":false,"counseling":false,"repeatedYear":false}
                  ],
                  "academicRecord":{"requestedCredits":75.0,"earnedCredits":75.0,
                                    "gpaSum":262.50,"gpa":3.50,"arithmeticAverage":85.00,"passFailCredits":12.0},
                  "certificate":{"requestedCredits":72.0,"earnedCredits":72.0,
                                 "gpaSum":252.00,"gpa":3.50,"arithmeticAverage":85.00,"passFailCredits":12.0},
                  "detailsByTerm":{
                    "2025-2학기":[
                      {"score":"95","grade":"A0","courseName":"운영체제","courseCode":"21500001",
                       "credits":3.0,"professor":"김교수","remark":""},
                      {"score":"88","grade":"B+","courseName":"알고리즘","courseCode":"21500002",
                       "credits":3.0,"professor":"이교수","remark":""}
                    ]
                  }
                }
                """;

        String compact = chatService.compactAndCap("get_my_grades", rawGradesJson);

        // 허용 — count, link, 누적 GPA, 학기별 GPA 이력 (year/term/gpa만)
        assertThat(compact)
                .contains("\"count\":2")
                .contains("\"link\":\"/grades\"")
                .contains("\"academicRecord\"")
                .contains("\"history\"");

        // 여전히 차단 — per-course 데이터, 집계 필드, certificate, 석차
        assertThat(compact)
                .doesNotContain("262.50")       // gpaSum
                .doesNotContain("85.00")        // arithmeticAverage
                .doesNotContain("18.0")         // history earnedCredits (gpa만 노출)
                .doesNotContain("95")           // score
                .doesNotContain("88")           // score
                .doesNotContain("A0")           // letter grade
                .doesNotContain("B+")           // letter grade
                .doesNotContain("운영체제")
                .doesNotContain("알고리즘")
                .doesNotContain("21500001")
                .doesNotContain("21500002")
                .doesNotContain("김교수")
                .doesNotContain("이교수")
                .doesNotContain("50/100")
                .doesNotContain("60/100")
                .doesNotContain("certificate")
                .doesNotContain("detailsByTerm");
    }

    @Test
    void gradesCompactReturnsZeroCountWhenDetailsByTermIsEmpty() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("noop").reply("noop", "noop")),
                List.of("noop"));
        String rawGradesJson = """
                {
                  "history":[
                    {"year":2025,"term":"2학기","requestedCredits":18.0,"earnedCredits":18.0,
                     "passFailCredits":3.0,"gpa":3.50,"gpaSum":63.00,"arithmeticAverage":85.00,
                     "rankInTerm":"50/100","rankOverall":"60/100",
                     "academicWarning":false,"counseling":false,"repeatedYear":false}
                  ],
                  "academicRecord":{"requestedCredits":75.0,"earnedCredits":75.0,
                                    "gpaSum":262.50,"gpa":3.50,"arithmeticAverage":85.00,"passFailCredits":12.0},
                  "certificate":{"requestedCredits":72.0,"earnedCredits":72.0,
                                 "gpaSum":252.00,"gpa":3.50,"arithmeticAverage":85.00,"passFailCredits":12.0},
                  "detailsByTerm":{}
                }
                """;

        String compact = chatService.compactAndCap("get_my_grades", rawGradesJson);

        assertThat(compact)
                .contains("\"count\":0")
                .contains("\"link\":\"/grades\"")
                .contains("\"academicRecord\"")
                .contains("\"history\"");
    }

    /**
     * Pins Task 16 spec §6 #6 — schedule rows ARE allowed in LLM prompts
     * but only in a tight compact format. Strip fields the chat answer
     * never needs (dayLabel — derivable from dayOfWeek, timeRange —
     * derivable from period, professor — not required to answer "내일
     * 1교시 뭐야?"). Keep dayOfWeek/period/course/room.
     */
    @Test
    void scheduleCompactKeepsCompactRowAndStripsProfessorTimeRangeAndDayLabel() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("noop").reply("noop", "noop")),
                List.of("noop"));
        String rawScheduleJson = """
                {
                  "enrollmentYear":2022,
                  "currentYear":2025,
                  "currentTerm":2,
                  "terms":[
                    {"year":2025,"term":2,"entries":[
                      {"dayOfWeek":1,"dayLabel":"월","period":1,"timeRange":"09:00-09:50",
                       "course":"운영체제","professor":"김교수","room":"정보과학관 401"},
                      {"dayOfWeek":3,"dayLabel":"수","period":3,"timeRange":"10:30-11:45",
                       "course":"알고리즘","professor":"이교수","room":"정보과학관 30100 (강의실A)"}
                    ]}
                  ]
                }
                """;

        String compact = chatService.compactAndCap("get_my_schedule", rawScheduleJson);

        // 허용 — compact row format (요일·교시·과목·강의실)
        assertThat(compact)
                .contains("\"enrollmentYear\":2022")
                .contains("\"currentYear\":2025")
                .contains("\"currentTerm\":2")
                .contains("\"year\":2025")
                .contains("\"term\":2")
                .contains("\"dayOfWeek\":1")
                .contains("\"period\":1")
                .contains("\"course\":\"운영체제\"")
                .contains("\"room\":\"정보과학관 401\"")
                .contains("\"course\":\"알고리즘\"");
        // 차단 — chat 답변에 필요 없는 메타
        assertThat(compact)
                .doesNotContain("dayLabel")
                .doesNotContain("timeRange")
                .doesNotContain("09:00-09:50")
                .doesNotContain("10:30-11:45")
                .doesNotContain("professor")
                .doesNotContain("김교수")
                .doesNotContain("이교수");
    }

    @Test
    void chapelCompactDropsPersonalAbsenceApplicationDetails() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("noop").reply("noop", "noop")),
                List.of("noop"));
        String rawChapelJson = """
                {
                  "year":2026,
                  "semester":"1학기",
                  "absenceAllowedMinutes":2,
                  "absenceUsedMinutes":1,
                  "result":"진행중",
                  "absenceApplications":[
                    {"category":"병무관계","startDate":"2026.05.14","endDate":"2026.05.20",
                     "reason":"예비군","status":"승인"}
                  ]
                }
                """;

        String compact = chatService.compactAndCap("get_my_chapel_info", rawChapelJson);

        assertThat(compact)
                .contains("\"absenceUsedMinutes\":1")
                .contains("\"result\":\"진행중\"")
                .doesNotContain("absenceApplications")
                .doesNotContain("병무관계")
                .doesNotContain("예비군")
                .doesNotContain("2026.05.14");
    }

    @Test
    void librarySeatCompactKeepsCountsAndDropsIndividualSeatData() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("noop").reply("noop", "noop")),
                List.of("noop"));
        String rawSeatJson = """
                {
                  "floor":2,
                  "floorLabel":"2층",
                  "totalSeats":112,
                  "availableSeats":1,
                  "reservedSeats":111,
                  "outOfServiceSeats":0,
                  "zones":[
                    {"label":"숭실스퀘어ON(2F)","total":112,"available":1,
                     "seatIds":["2-A-001"],
                     "seats":[{"id":"2-A-001","label":"A-1","status":"available"}]}
                  ]
                }
                """;

        String compact = chatService.compactAndCap("get_library_seat_status", rawSeatJson);

        assertThat(compact)
                .contains("\"floor\":2")
                .contains("\"availableSeats\":1")
                .contains("\"label\":\"숭실스퀘어ON(2F)\"")
                .doesNotContain("seatIds")
                .doesNotContain("\"seats\"")
                .doesNotContain("2-A-001")
                .doesNotContain("A-1");
    }

    @Test
    void noticeListCompactKeepsDisplayFieldsAndDropsUnusedStatus() {
        LlmChatService chatService = chatService(
                List.of(new FakeProvider("noop").reply("noop", "noop")),
                List.of("noop"));
        String rawNoticeJson = """
                {
                  "items":[
                    {"title":"장학금 신청 안내","link":"https://example.test/notice/1",
                     "date":"2026-05-26","status":"진행","department":"학생처","category":"장학"}
                  ],
                  "currentPage":1,
                  "totalPages":2
                }
                """;

        String compact = chatService.compactAndCap("get_recent_notices", rawNoticeJson);

        assertThat(compact)
                .contains("\"title\":\"장학금 신청 안내\"")
                .contains("\"link\":\"https://example.test/notice/1\"")
                .contains("\"category\":\"장학\"")
                .contains("\"department\":\"학생처\"")
                .doesNotContain("\"status\"");
    }

    private static org.mockito.ArgumentMatcher<McpSchema.CallToolRequest> named(String toolName) {
        return request -> request != null && toolName.equals(request.name());
    }

    private static McpSchema.CallToolResult toolTextResult(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.<McpSchema.Content>of(new McpSchema.TextContent(text)))
                .isError(Boolean.FALSE)
                .build();
    }

    private LlmChatService chatService(List<LlmProvider> providers, List<String> providerOrder) {
        return chatService(providers, providerOrder, List.of("missing-private-provider"), 1);
    }

    private LlmChatService chatService(
            List<LlmProvider> providers,
            List<String> providerOrder,
            List<String> privateProviderOrder,
            int availabilityVerificationPasses
    ) {
        LlmChatProperties properties = new LlmChatProperties();
        properties.setProviderOrder(providerOrder);
        properties.setPrivateProviderOrder(privateProviderOrder);
        properties.setAvailabilityVerificationPasses(availabilityVerificationPasses);
        return chatService(providers, properties);
    }

    private LlmChatProperties properties(
            List<String> providerOrder,
            List<String> privateProviderOrder,
            int availabilityVerificationPasses
    ) {
        LlmChatProperties properties = new LlmChatProperties();
        properties.setProviderOrder(providerOrder);
        properties.setPrivateProviderOrder(privateProviderOrder);
        properties.setAvailabilityVerificationPasses(availabilityVerificationPasses);
        return properties;
    }

    private LlmChatService chatService(List<LlmProvider> providers, LlmChatProperties properties) {
        stubMcpToolDiscovery();
        return chatService(providers, properties, List.of(mcpClient));
    }

    private LlmChatService chatService(
            List<LlmProvider> providers,
            LlmChatProperties properties,
            List<McpSyncClient> mcpClients
    ) {
        return new LlmChatService(
                properties,
                providers,
                new ObjectMapper(),
                new ChatConversationStore(new ChatMemoryProperties()),
                scheduleService,
                gradesService,
                lmsAssignmentsService,
                librarySeatService,
                libraryLoansService,
                mcpClients,
                chapelService,
                graduationService,
                scholarshipService
        );
    }

    private LlmChatService chatServiceWithClock(
            List<LlmProvider> providers, List<String> providerOrder, Clock clock) {
        stubMcpToolDiscovery();
        LlmChatProperties props = new LlmChatProperties();
        props.setProviderOrder(providerOrder);
        props.setPrivateProviderOrder(List.of("missing-private-provider"));
        props.setAvailabilityVerificationPasses(0);
        return new LlmChatService(
                props,
                providers,
                new ObjectMapper(),
                new ChatConversationStore(new ChatMemoryProperties()),
                scheduleService,
                gradesService,
                lmsAssignmentsService,
                librarySeatService,
                libraryLoansService,
                List.of(mcpClient),
                clock,
                chapelService,
                graduationService,
                scholarshipService
        );
    }

    private void stubMcpToolDiscovery() {
        doReturn(true).when(mcpClient).isInitialized();
        doReturn(canonicalListToolsResult()).when(mcpClient).listTools();
    }

    private static McpSchema.ListToolsResult canonicalListToolsResult() {
        return new McpSchema.ListToolsResult(
                List.of(
                        canonicalTool("get_today_meal",
                                "오늘 숭실대학교 학생식당 메뉴를 조회합니다.",
                                emptyObjectSchema()),
                        canonicalTool("get_meal_by_date",
                                "지정한 날짜의 숭실대학교 학생식당 메뉴를 조회합니다.",
                                requiredStringSchema("date", "yyyy-MM-dd 형식의 날짜")),
                        canonicalTool("get_dorm_weekly_meal",
                                "이번 주 숭실대학교 기숙사 식단을 조회합니다.",
                                emptyObjectSchema()),
                        canonicalTool("search_campus_facilities",
                                "숭실대학교 캠퍼스 시설을 검색합니다.",
                                requiredStringSchema("query", "검색어. 비워두지 마세요.")),
                        canonicalTool("get_library_seat_status",
                                "숭실대학교 중앙도서관의 좌석 현황을 층별로 조회합니다. LIBRARY 연동이 필요합니다.",
                                requiredIntegerSchema("floor", "도서관 층 코드 (2, 5, 6)")),
                        canonicalTool("search_library_book",
                                "숭실대학교 중앙도서관 소장 도서를 키워드로 검색합니다.",
                                requiredStringSchema("query", "검색어 (제목/저자/출판 키워드, 1~64자)")),
                        canonicalTool("start_auth", "로그인을 시작합니다.", emptyObjectSchema()),
                        canonicalTool("get_auth_status", "로그인 상태를 확인합니다.", emptyObjectSchema()),
                        canonicalTool("logout_provider", "연동을 해제합니다.", emptyObjectSchema()),
                        canonicalTool("logout_all", "모든 연동을 해제합니다.", emptyObjectSchema())
                ),
                null
        );
    }

    private static McpSchema.Tool canonicalTool(String name, String description, McpSchema.JsonSchema schema) {
        return new McpSchema.Tool(name, null, description, schema, null, null, null);
    }

    private static McpSchema.JsonSchema emptyObjectSchema() {
        return new McpSchema.JsonSchema("object", Map.of(), List.of(), Boolean.FALSE, null, null);
    }

    private static McpSchema.JsonSchema requiredStringSchema(String property, String description) {
        return new McpSchema.JsonSchema(
                "object",
                Map.of(property, Map.of("type", "string", "description", description)),
                List.of(property),
                Boolean.FALSE,
                null,
                null
        );
    }

    private static McpSchema.JsonSchema requiredIntegerSchema(String property, String description) {
        return new McpSchema.JsonSchema(
                "object",
                Map.of(property, Map.of("type", "integer", "description", description)),
                List.of(property),
                Boolean.FALSE,
                null,
                null
        );
    }

    private static final class FakeProvider implements LlmProvider {

        private final String name;
        private final Queue<Object> outcomes = new ArrayDeque<>();
        private final List<LlmPrivacyMode> privacyModes = new ArrayList<>();
        private final List<LlmCompletionRequest> requests = new ArrayList<>();
        private boolean configured = true;
        private int callCount;

        private FakeProvider(String name) {
            this.name = name;
        }

        private FakeProvider reply(String model, String content) {
            outcomes.add(new LlmCompletionResult(
                    name,
                    model,
                    new OpenAiChatCompletionResponse.Message("assistant", content, List.of())
            ));
            return this;
        }

        private FakeProvider toolCall(String model, OpenAiToolCall toolCall) {
            return toolCalls(model, List.of(toolCall));
        }

        private FakeProvider toolCalls(String model, List<OpenAiToolCall> toolCalls) {
            outcomes.add(new LlmCompletionResult(
                    name,
                    model,
                    new OpenAiChatCompletionResponse.Message("assistant", null, toolCalls)
            ));
            return this;
        }

        private FakeProvider fail(LlmProviderException exception) {
            outcomes.add(exception);
            return this;
        }

        private FakeProvider unconfigured() {
            configured = false;
            return this;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public LlmCompletionResult complete(LlmCompletionRequest request) {
            callCount++;
            privacyModes.add(request.privacyMode());
            requests.add(request);
            Object outcome = outcomes.remove();
            if (outcome instanceof LlmProviderException exception) {
                throw exception;
            }
            return (LlmCompletionResult) outcome;
        }

        private int callCount() {
            return callCount;
        }

        private LlmPrivacyMode lastPrivacyMode() {
            return privacyModes.get(privacyModes.size() - 1);
        }

        private LlmCompletionRequest request(int index) {
            return requests.get(index);
        }
    }
}
