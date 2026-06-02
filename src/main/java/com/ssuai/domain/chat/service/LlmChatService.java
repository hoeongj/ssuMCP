package com.ssuai.domain.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.ssuai.domain.chat.config.LlmChatProperties;
import com.ssuai.domain.chat.dto.ChatResponse;
import com.ssuai.domain.chat.dto.OpenAiChatCompletionRequest;
import com.ssuai.domain.chat.dto.OpenAiChatCompletionResponse;
import com.ssuai.domain.chat.dto.OpenAiToolCall;
import com.ssuai.domain.chat.memory.ChatConversationStore;
import com.ssuai.domain.chat.memory.ChatConversationStore.Turn;
import com.ssuai.domain.chat.service.llm.LlmCompletionRequest;
import com.ssuai.domain.chat.service.llm.LlmCompletionResult;
import com.ssuai.domain.chat.service.llm.LlmPrivacyMode;
import com.ssuai.domain.chat.service.llm.LlmProvider;
import com.ssuai.domain.chat.service.llm.LlmProviderException;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.mcp.LibraryToolContext;
import com.ssuai.domain.library.service.LibraryLoansService;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.domain.lms.mcp.LmsToolContext;
import com.ssuai.domain.lms.service.LmsAssignmentsService;
import com.ssuai.domain.saint.mcp.SaintToolContext;
import com.ssuai.domain.saint.service.SaintChapelService;
import com.ssuai.domain.saint.service.SaintGraduationService;
import com.ssuai.domain.saint.service.SaintGradesService;
import com.ssuai.domain.saint.service.SaintScheduleService;
import com.ssuai.domain.saint.service.SaintScholarshipService;
import com.ssuai.global.exception.ChatUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.global.exception.SaintSessionExpiredException;

@Service
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "llm")
public class LlmChatService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(LlmChatService.class);

    private static final String SCOPE_GUIDANCE =
            "아직은 그 정보는 지원하지 않아요. 지금은 학식, 기숙사 식단, 캠퍼스 시설, 도서관 도서 검색, 공지사항, 그리고 (로그인된 경우) 도서관 좌석·대출 현황과 본인 시간표·성적·채플·졸업요건·장학금·LMS 과제를 도와줄 수 있어요.";

    private static final String SECRET_GUIDANCE =
            "비밀번호, 쿠키, 세션, API key 같은 비밀 정보는 입력하지 말아주세요. "
                    + "학식, 기숙사 식단, 캠퍼스 시설, 도서관 도서 검색, 공지사항, 그리고 "
                    + "(로그인된 경우) 도서관 좌석·대출 현황과 본인 시간표·성적·채플·졸업요건·장학금·LMS 과제를 도와줄 수 있어요.";

    private static final String SAINT_SESSION_GUIDANCE =
            "u-SAINT 로그인이 필요한 정보예요. 먼저 SmartID 로 로그인하고 다시 물어봐 주세요.";

    private static final String SAINT_SESSION_EXPIRED_GUIDANCE =
            "u-SAINT 세션이 만료됐어요. SmartID 로 다시 로그인하고 물어봐 주세요.";

    private static final String LMS_SESSION_GUIDANCE =
            "LMS 로그인이 필요한 정보예요. 먼저 LMS(SmartID)로 로그인하고 다시 물어봐 주세요.";

    private static final String LMS_SESSION_EXPIRED_GUIDANCE =
            "LMS 세션이 만료됐어요. LMS(SmartID)로 다시 로그인하고 물어봐 주세요.";

    private static final String LIBRARY_SESSION_GUIDANCE =
            "도서관 세션 연동이 필요한 정보예요. 대시보드의 도서관 카드에서 '도서관 연동' 버튼을 누르고 학번과 비밀번호를 입력해 주세요.";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Set<String> CHAT_EXCLUDED_TOOLS = Set.of(
            "start_auth", "get_auth_status", "logout_provider", "logout_all");
    private static final Set<String> PRIVATE_RESULT_TOOLS = Set.of(
            "get_my_schedule",
            "get_my_grades",
            "get_my_chapel_info",
            "check_graduation_requirements",
            "get_my_scholarships",
            "get_my_assignments",
            "get_library_seat_status",
            "get_my_library_loans");

    private final LlmChatProperties properties;
    private final Map<String, LlmProvider> providersByName;
    private final ObjectMapper objectMapper;
    private final ChatConversationStore conversationStore;
    private final SaintScheduleService scheduleService;
    private final SaintGradesService gradesService;
    private final SaintChapelService chapelService;
    private final SaintGraduationService graduationService;
    private final SaintScholarshipService scholarshipService;
    private final LmsAssignmentsService lmsAssignmentsService;
    private final LibrarySeatService librarySeatService;
    private final LibraryLoansService libraryLoansService;
    private final Clock clock;
    private ToolResultCompactor toolResultCompactor;
    private volatile List<OpenAiChatCompletionRequest.Tool> cachedChatTools;
    private volatile Map<String, McpSyncClient> toolClientIndex;

    private final List<McpSyncClient> mcpClients;
    private SystemPromptBuilder systemPromptBuilder;

    @Autowired
    public LlmChatService(
            LlmChatProperties properties,
            List<LlmProvider> providers,
            ObjectMapper objectMapper,
            ChatConversationStore conversationStore,
            SaintScheduleService scheduleService,
            SaintGradesService gradesService,
            LmsAssignmentsService lmsAssignmentsService,
            LibrarySeatService librarySeatService,
            LibraryLoansService libraryLoansService,
            @Lazy List<McpSyncClient> mcpClients,
            SaintChapelService chapelService,
            SaintGraduationService graduationService,
            SaintScholarshipService scholarshipService,
            SystemPromptBuilder systemPromptBuilder,
            ToolResultCompactor toolResultCompactor
    ) {
        this(properties, providers, objectMapper, conversationStore,
                scheduleService, gradesService, lmsAssignmentsService, librarySeatService, libraryLoansService,
                mcpClients, Clock.system(KST), chapelService, graduationService, scholarshipService);
        this.systemPromptBuilder = systemPromptBuilder;
        this.toolResultCompactor = toolResultCompactor;
    }

    // Constructor for backward compatibility in tests
    LlmChatService(
            LlmChatProperties properties,
            List<LlmProvider> providers,
            ObjectMapper objectMapper,
            ChatConversationStore conversationStore,
            SaintScheduleService scheduleService,
            SaintGradesService gradesService,
            LmsAssignmentsService lmsAssignmentsService,
            LibrarySeatService librarySeatService,
            LibraryLoansService libraryLoansService,
            List<McpSyncClient> mcpClients,
            SaintChapelService chapelService,
            SaintGraduationService graduationService,
            SaintScholarshipService scholarshipService
    ) {
        this(properties, providers, objectMapper, conversationStore,
                scheduleService, gradesService, lmsAssignmentsService, librarySeatService, libraryLoansService,
                mcpClients, Clock.system(KST), chapelService, graduationService, scholarshipService);
    }

    LlmChatService(
            LlmChatProperties properties,
            List<LlmProvider> providers,
            ObjectMapper objectMapper,
            ChatConversationStore conversationStore,
            SaintScheduleService scheduleService,
            SaintGradesService gradesService,
            LmsAssignmentsService lmsAssignmentsService,
            LibrarySeatService librarySeatService,
            LibraryLoansService libraryLoansService,
            List<McpSyncClient> mcpClients,
            Clock clock,
            SaintChapelService chapelService,
            SaintGraduationService graduationService,
            SaintScholarshipService scholarshipService
    ) {
        this.properties = properties;
        this.providersByName = providers.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::name, Function.identity()));
        this.objectMapper = objectMapper;
        this.conversationStore = conversationStore;
        this.scheduleService = scheduleService;
        this.gradesService = gradesService;
        this.chapelService = chapelService;
        this.graduationService = graduationService;
        this.scholarshipService = scholarshipService;
        this.lmsAssignmentsService = lmsAssignmentsService;
        this.librarySeatService = librarySeatService;
        this.libraryLoansService = libraryLoansService;
        this.mcpClients = mcpClients;
        this.clock = clock;
        this.systemPromptBuilder = new SystemPromptBuilder(clock);
        this.toolResultCompactor = new ToolResultCompactor(objectMapper);
    }

    /**
     * Emits a per-request system message giving the model today's KST date.
     * Delegated to SystemPromptBuilder.
     */
    String buildTodayContextMessage() {
        return systemPromptBuilder.buildTodayContextMessage();
    }

    /**
     * Emits the user authentication state context message.
     * Delegated to SystemPromptBuilder.
     */
    String buildAuthContextMessage(String studentId) {
        return systemPromptBuilder.buildAuthContextMessage(studentId);
    }

    @Override
    public ChatResponse reply(String conversationId, String message, String studentId) {
        if (looksLikeSecretInput(message)) {
            // Never persist the user message: it may contain secrets.
            return new ChatResponse(conversationId, SECRET_GUIDANCE);
        }
        if (looksLikeOutOfScopeRequest(message)) {
            conversationStore.appendUser(conversationId, message);
            conversationStore.appendAssistant(conversationId, SCOPE_GUIDANCE);
            return new ChatResponse(conversationId, SCOPE_GUIDANCE);
        }

        Instant startedAt = Instant.now();
        int messageLength = message == null ? 0 : message.length();
        boolean authenticated = studentId != null && !studentId.isBlank();
        log.info("chat reply started: conversationId={} messageLength={} authenticated={}",
                conversationId, messageLength, authenticated);

        List<Turn> history = conversationStore.history(conversationId);
        conversationStore.appendUser(conversationId, message);

        try {
            LlmPrivacyMode privacyMode = authenticated || conversationStore.isPrivate(conversationId)
                    ? LlmPrivacyMode.PRIVATE
                    : LlmPrivacyMode.PUBLIC;
            ChatResponse response = callLlm(conversationId, message, history,
                    privacyMode, studentId);
            conversationStore.appendAssistant(conversationId, response.reply());
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            log.info("chat reply completed: conversationId={} messageLength={} latencyMs={} historyTurns={}",
                    conversationId, messageLength, latencyMs, history.size());
            return response;
        } catch (ChatUnavailableException exception) {
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            log.warn("chat reply failed: conversationId={} messageLength={} latencyMs={} failureType={}",
                    conversationId, messageLength, latencyMs, exception.getClass().getSimpleName());
            throw exception;
        } catch (RuntimeException exception) {
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            log.warn("chat reply failed: conversationId={} messageLength={} latencyMs={} failureType={}",
                    conversationId, messageLength, latencyMs, exception.getClass().getSimpleName());
            throw new ChatUnavailableException(exception);
        }
    }

    private ChatResponse callLlm(
            String conversationId,
            String message,
            List<Turn> history,
            LlmPrivacyMode privacyMode,
            String studentId
    ) {
        List<OpenAiChatCompletionRequest.Message> baseMessages = new ArrayList<>();
        baseMessages.add(OpenAiChatCompletionRequest.systemMessage(systemPromptBuilder.getBaseSystemPrompt()));
        baseMessages.add(OpenAiChatCompletionRequest.systemMessage(buildTodayContextMessage()));
        baseMessages.add(OpenAiChatCompletionRequest.systemMessage(buildAuthContextMessage(studentId)));
        for (Turn turn : history) {
            if (ChatConversationStore.ROLE_USER.equals(turn.role())) {
                baseMessages.add(OpenAiChatCompletionRequest.userMessage(turn.content()));
            } else if (ChatConversationStore.ROLE_ASSISTANT.equals(turn.role())) {
                baseMessages.add(OpenAiChatCompletionRequest.assistantMessage(turn.content()));
            }
        }
        baseMessages.add(OpenAiChatCompletionRequest.userMessage(message));

        LlmCompletionResult firstResult = completeAcrossProviders(new LlmCompletionRequest(
                privacyMode,
                baseMessages,
                chatTools(),
                "auto"
        ));
        OpenAiChatCompletionResponse.Message firstMessage = firstResult.message();
        List<OpenAiToolCall> toolCalls = safeToolCalls(firstMessage.toolCalls());

        if (toolCalls.isEmpty()) {
            log.info("chat provider selected: conversationId={} provider={} model={} toolCalls=0",
                    conversationId, firstResult.providerName(), firstResult.model());
            return new ChatResponse(conversationId, requireContent(firstMessage.content()),
                    formatModel(firstResult));
        }

        List<OpenAiChatCompletionRequest.Message> messages = new ArrayList<>(baseMessages);
        messages.add(OpenAiChatCompletionRequest.assistantToolCallMessage(firstMessage.content(), toolCalls));
        // Bind linked web-session context during direct private-service
        // dispatch. External MCP clients use mcp_session_id instead.
        try (SaintToolContext.Scope ignored = SaintToolContext.withStudentId(studentId);
             LmsToolContext.Scope ignoredLms = LmsToolContext.withStudentId(studentId);
             LibraryToolContext.Scope ignoredLibrary =
                     LibraryToolContext.withSessionKey(LibraryToolContext.currentSessionKey())) {
            for (int index = 0; index < toolCalls.size(); index++) {
                OpenAiToolCall toolCall = toolCalls.get(index);
                String toolCallId = toolCall.id() == null || toolCall.id().isBlank()
                        ? "call_" + index
                        : toolCall.id();
                String content = index < maxToolCalls()
                        ? executeToolCall(toolCall, studentId)
                        : toolError("한 번에 처리할 수 있는 도구 호출 수를 초과했습니다. 한두 가지씩 나눠서 물어봐 주세요.");
                messages.add(OpenAiChatCompletionRequest.toolResultMessage(toolCallId, content));
            }
        }

        boolean hasPrivateResult = toolCalls.stream()
                .map(toolCall -> toolCall.function() == null ? "" : toolCall.function().name())
                .anyMatch(PRIVATE_RESULT_TOOLS::contains);
        if (hasPrivateResult) {
            conversationStore.markPrivate(conversationId);
        }
        LlmPrivacyMode finalPrivacyMode = hasPrivateResult ? LlmPrivacyMode.PRIVATE : privacyMode;
        LlmCompletionResult finalResult = completeAcrossProviders(new LlmCompletionRequest(
                finalPrivacyMode,
                messages,
                null,
                null
        ));
        log.info("chat provider selected: conversationId={} provider={} model={} toolCalls={}",
                conversationId, finalResult.providerName(), finalResult.model(), toolCalls.size());
        return new ChatResponse(conversationId, requireContent(finalResult.message().content()),
                formatModel(finalResult));
    }

    private int maxToolCalls() {
        return Math.max(1, properties.getMaxToolCalls());
    }

    private LlmCompletionResult completeAcrossProviders(LlmCompletionRequest request) {
        List<ProviderAttempt> attempts = providerAttempts(request.privacyMode());
        if (attempts.isEmpty()) {
            throw new ChatUnavailableException();
        }

        LlmProviderException lastFailure = null;
        int totalPasses = Math.max(1, properties.getAvailabilityVerificationPasses() + 1);
        for (int pass = 1; pass <= totalPasses; pass++) {
            for (ProviderAttempt attempt : attempts) {
                try {
                    return attempt.provider().complete(withPrivacyMode(request, attempt.privacyMode()));
                } catch (LlmProviderException exception) {
                    if (!exception.fallbackable()) {
                        throw new ChatUnavailableException(exception);
                    }
                    lastFailure = exception;
                    log.info("llm provider fallback: provider={} privacyMode={} pass={} statusCode={}",
                            exception.providerName(), attempt.privacyMode(), pass, exception.statusCode());
                }
            }
        }

        throw new ChatUnavailableException(lastFailure);
    }

    private List<ProviderAttempt> providerAttempts(LlmPrivacyMode privacyMode) {
        if (privacyMode == LlmPrivacyMode.PRIVATE) {
            return capProviderAttempts(orderedProviders(properties.getPrivateProviderOrder()).stream()
                    .map(provider -> new ProviderAttempt(provider, LlmPrivacyMode.PRIVATE))
                    .toList());
        }

        List<ProviderAttempt> attempts = new ArrayList<>();
        orderedProviders(properties.getProviderOrder()).stream()
                .map(provider -> new ProviderAttempt(provider, LlmPrivacyMode.PUBLIC))
                .forEach(attempts::add);
        orderedProviders(properties.getPrivateProviderOrder()).stream()
                .map(provider -> new ProviderAttempt(provider, LlmPrivacyMode.PRIVATE))
                .forEach(attempts::add);
        return capProviderAttempts(attempts);
    }

    private List<LlmProvider> orderedProviders(List<String> providerOrder) {
        if (providerOrder == null || providerOrder.isEmpty()) {
            return providersByName.values().stream()
                    .filter(LlmProvider::isConfigured)
                    .toList();
        }

        return providerOrder.stream()
                .map(providersByName::get)
                .filter(provider -> provider != null && provider.isConfigured())
                .toList();
    }

    private List<ProviderAttempt> capProviderAttempts(List<ProviderAttempt> attempts) {
        int maxAttempts = Math.max(1, properties.getMaxProviderAttempts());
        if (attempts.size() <= maxAttempts) {
            return attempts;
        }
        return attempts.subList(0, maxAttempts);
    }

    private static LlmCompletionRequest withPrivacyMode(
            LlmCompletionRequest request,
            LlmPrivacyMode privacyMode
    ) {
        if (request.privacyMode() == privacyMode) {
            return request;
        }
        return new LlmCompletionRequest(
                privacyMode,
                request.messages(),
                request.tools(),
                request.toolChoice()
        );
    }

    private record ProviderAttempt(
            LlmProvider provider,
            LlmPrivacyMode privacyMode
    ) {
    }

    private List<OpenAiChatCompletionRequest.Tool> chatTools() {
        List<OpenAiChatCompletionRequest.Tool> snapshot = cachedChatTools;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (cachedChatTools == null) {
                cachedChatTools = discoverChatTools();
            }
            return cachedChatTools;
        }
    }

    private List<OpenAiChatCompletionRequest.Tool> discoverChatTools() {
        List<OpenAiChatCompletionRequest.Tool> tools = new ArrayList<>();
        boolean anySuccess = false;
        for (McpSyncClient client : safeMcpClients()) {
            try {
                if (!client.isInitialized()) {
                    client.initialize();
                }
                McpSchema.ListToolsResult listing = client.listTools();
                List<OpenAiChatCompletionRequest.Tool> fromClient = safeTools(listing).stream()
                        .filter(Objects::nonNull)
                        .filter(tool -> !CHAT_EXCLUDED_TOOLS.contains(tool.name()))
                        .map(this::mapMcpToolToOpenAi)
                        .toList();
                tools.addAll(fromClient);
                anySuccess = true;
            } catch (RuntimeException exception) {
                log.warn("multi-mcp: discoverChatTools failed for a client: error={}",
                        exception.getClass().getSimpleName());
            }
        }
        if (!anySuccess) {
            throw new ChatUnavailableException(new RuntimeException("no MCP client available"));
        }
        log.info("mcp chat tools discovered (all clients): count={}", tools.size());
        return tools;
    }

    private Map<String, McpSyncClient> getToolClientIndex() {
        Map<String, McpSyncClient> snapshot = toolClientIndex;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (toolClientIndex == null) {
                Map<String, McpSyncClient> index = new LinkedHashMap<>();
                for (McpSyncClient client : safeMcpClients()) {
                    try {
                        if (!client.isInitialized()) {
                            client.initialize();
                        }
                        safeTools(client.listTools()).stream()
                                .filter(Objects::nonNull)
                                .forEach(tool -> index.putIfAbsent(tool.name(), client));
                    } catch (RuntimeException exception) {
                        log.warn("multi-mcp: listTools failed for a client: error={}",
                                exception.getClass().getSimpleName());
                    }
                }
                toolClientIndex = index;
            }
            return toolClientIndex;
        }
    }

    private McpSyncClient clientFor(String toolName) {
        McpSyncClient client = getToolClientIndex().get(toolName);
        if (client != null) {
            return client;
        }
        return firstAvailableMcpClient();
    }

    private List<McpSyncClient> safeMcpClients() {
        if (mcpClients == null || mcpClients.isEmpty()) {
            return List.of();
        }
        return mcpClients.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private McpSyncClient firstAvailableMcpClient() {
        return safeMcpClients().stream()
                .findFirst()
                .orElse(null);
    }

    private static List<McpSchema.Tool> safeTools(McpSchema.ListToolsResult listing) {
        if (listing == null || listing.tools() == null) {
            return List.of();
        }
        return listing.tools();
    }

    private OpenAiChatCompletionRequest.Tool mapMcpToolToOpenAi(McpSchema.Tool tool) {
        String description = tool.description() == null ? "" : tool.description();
        return new OpenAiChatCompletionRequest.Tool(
                "function",
                new OpenAiChatCompletionRequest.FunctionDefinition(
                        tool.name(),
                        description,
                        mapInputSchema(tool.inputSchema())
                )
        );
    }

    private static Map<String, Object> mapInputSchema(McpSchema.JsonSchema schema) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        if (schema == null) {
            mapped.put("type", "object");
            mapped.put("properties", Map.of());
            mapped.put("additionalProperties", false);
            return mapped;
        }
        mapped.put("type", schema.type() == null ? "object" : schema.type());
        mapped.put("properties", schema.properties() == null ? Map.of() : schema.properties());
        if (schema.required() != null && !schema.required().isEmpty()) {
            mapped.put("required", schema.required());
        }
        mapped.put("additionalProperties",
                schema.additionalProperties() == null ? Boolean.FALSE : schema.additionalProperties());
        return mapped;
    }

    private String executeToolCall(OpenAiToolCall toolCall, String studentId) {
        String toolName = toolCall.function() == null ? "" : toolCall.function().name();
        try {
            return switch (toolName) {
                case "get_today_meal" -> callMcp(toolName, restaurantArgs(toolCall));
                case "get_meal_by_date" -> {
                    LinkedHashMap<String, Object> args = new LinkedHashMap<>();
                    args.put("date", requiredArgument(toolCall, "date"));
                    args.putAll(restaurantArgs(toolCall));
                    yield callMcp(toolName, args);
                }
                case "get_dorm_weekly_meal" -> callMcp(toolName, Map.of());
                case "search_campus_facilities" -> {
                    String query = optionalArgument(toolCall, "query").trim();
                    if (query.isBlank()) {
                        yield toolError("시설 검색은 검색어가 필요합니다. 예: 카페, 복사, 편의점, 학생식당.");
                    }
                    yield callMcp(toolName, Map.of("query", query));
                }
                case "get_library_seat_status" -> {
                    int floor = requiredIntArgument(toolCall, "floor");
                    yield dispatchPrivateLibraryTool(
                            toolName, () -> librarySeatService.getSeatStatusForSession(
                                    LibraryFloor.fromCode(floor), LibraryToolContext.currentSessionKey()));
                }
                case "search_library_book" -> {
                    String query = optionalArgument(toolCall, "query").trim();
                    if (query.isBlank()) {
                        yield toolError("도서 검색은 검색어가 필요합니다. 예: 파이썬, 이펙티브 자바.");
                    }
                    LinkedHashMap<String, Object> args = new LinkedHashMap<>();
                    args.put("query", query);
                    optionalIntArgument(toolCall, "page").ifPresent(value -> args.put("page", value));
                    optionalIntArgument(toolCall, "size").ifPresent(value -> args.put("size", value));
                    yield callMcp(toolName, args);
                }
                case "get_my_schedule" -> dispatchPrivateSaintTool(
                        toolName, studentId, () -> scheduleService.fetchSchedule(studentId));
                case "get_my_grades" -> dispatchPrivateSaintTool(
                        toolName, studentId, () -> gradesService.fetchGrades(studentId));
                case "get_my_chapel_info" -> {
                    Integer year = optionalIntArgument(toolCall, "year").orElse(null);
                    String rawSemester = optionalArgument(toolCall, "semester");
                    String semester = rawSemester.isBlank() ? null : rawSemester;
                    yield dispatchPrivateSaintTool(
                            toolName, studentId,
                            () -> chapelService.fetchChapelInfo(studentId, year, semester));
                }
                case "check_graduation_requirements" -> dispatchPrivateSaintTool(
                        toolName, studentId,
                        () -> graduationService.fetchGraduationRequirements(studentId));
                case "get_my_scholarships" -> {
                    Integer year = optionalIntArgument(toolCall, "year").orElse(null);
                    yield dispatchPrivateSaintTool(
                            toolName, studentId,
                            () -> scholarshipService.fetchScholarships(studentId, year));
                }
                case "get_my_assignments" -> dispatchPrivateLmsTool(
                        toolName, studentId, () -> lmsAssignmentsService.fetchAssignments(studentId));
                case "get_my_library_loans" -> dispatchPrivateLibraryTool(
                        toolName, () -> libraryLoansService.getLoansForSession(
                                LibraryToolContext.currentSessionKey()));
                case "get_recent_notices" -> {
                    LinkedHashMap<String, Object> args = new LinkedHashMap<>();
                    String category = optionalArgument(toolCall, "category").trim();
                    if (!category.isBlank()) {
                        args.put("category", category);
                    }
                    optionalIntArgument(toolCall, "page").ifPresent(page -> args.put("page", page));
                    yield callMcp(toolName, args);
                }
                case "search_notices" -> {
                    String keyword = optionalArgument(toolCall, "keyword").trim();
                    if (keyword.isBlank()) {
                        yield toolError("검색어를 입력해 주세요.");
                    }
                    LinkedHashMap<String, Object> args = new LinkedHashMap<>();
                    args.put("keyword", keyword);
                    String category = optionalArgument(toolCall, "category").trim();
                    if (!category.isBlank()) {
                        args.put("category", category);
                    }
                    optionalIntArgument(toolCall, "page").ifPresent(page -> args.put("page", page));
                    yield callMcp(toolName, args);
                }
                case "list_notice_categories" -> callMcp(toolName, Map.of());
                case "get_notice_detail" -> callMcp(
                        toolName, Map.of("url", requiredArgument(toolCall, "url")));
                case "get_active_notices" -> {
                    LinkedHashMap<String, Object> args = new LinkedHashMap<>();
                    String category = optionalArgument(toolCall, "category").trim();
                    if (!category.isBlank()) {
                        args.put("category", category);
                    }
                    yield callMcp(toolName, args);
                }
                case "get_department_notices" -> {
                    LinkedHashMap<String, Object> args = new LinkedHashMap<>();
                    args.put("department", requiredArgument(toolCall, "department"));
                    optionalIntArgument(toolCall, "page").ifPresent(page -> args.put("page", page));
                    yield callMcp(toolName, args);
                }
                default -> {
                    Map<String, Object> args = rawArguments(toolCall);
                    yield callMcp(toolName, args);
                }
            };
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return toolError(exception.getMessage());
        }
    }

    /**
     * Run a u-SAINT private tool against the in-process service directly,
     * bypassing the MCP SSE round-trip. The loopback {@code mcpClient}
     * would dispatch the call to a separate servlet thread where
     * {@code SaintToolContext} 's thread-local cannot reach; calling the
     * service in-line keeps the authenticated student id local to the
     * chat thread. Compact policy ({@code compactAndCap}) still runs on
     * the way out, so the LLM never sees raw grade rows / schedule
     * professor names.
     */
    private String dispatchPrivateSaintTool(
            String toolName,
            String studentId,
            java.util.function.Supplier<Object> serviceCall
    ) {
        if (studentId == null || studentId.isBlank()) {
            log.info("chat private tool refused: tool={} reason=unauthenticated", toolName);
            return toolError(SAINT_SESSION_GUIDANCE);
        }
        // Audit log (Task 16 spec §8 #6): pin the intent — who asked for
        // what — before the connector runs. studentFp is a SHA-256 prefix,
        // never the raw student id; tool name is one of the literal
        // enum-like strings ("get_my_schedule" / "get_my_grades"). The
        // response payload (course names, grade letters, etc.) MUST NOT
        // appear in any log line on this code path; the only other log
        // below is post-fetch completion which again uses studentFp only.
        String studentFp = com.ssuai.domain.auth.saint.SaintSessionStore.fingerprint(studentId);
        log.info("chat private tool requested: tool={} studentFp={}", toolName, studentFp);
        try {
            Object response = serviceCall.get();
            String json = objectMapper.writeValueAsString(response);
            log.info("chat private tool completed: tool={} studentFp={}", toolName, studentFp);
            return toolResultCompactor.compactAndCap(toolName, json);
        } catch (SaintSessionExpiredException exception) {
            log.info("chat private tool expired: tool={} studentFp={}", toolName, studentFp);
            return toolError(SAINT_SESSION_EXPIRED_GUIDANCE);
        } catch (JsonProcessingException exception) {
            throw new ChatUnavailableException(exception);
        }
    }

    private String dispatchPrivateLmsTool(
            String toolName,
            String studentId,
            java.util.function.Supplier<Object> serviceCall
    ) {
        if (studentId == null || studentId.isBlank()) {
            log.info("chat private tool refused: tool={} reason=unauthenticated", toolName);
            return toolError(LMS_SESSION_GUIDANCE);
        }
        String studentFp = com.ssuai.domain.auth.lms.LmsSessionStore.fingerprint(studentId);
        log.info("chat private tool requested: tool={} studentFp={}", toolName, studentFp);
        try {
            Object response = serviceCall.get();
            String json = objectMapper.writeValueAsString(response);
            log.info("chat private tool completed: tool={} studentFp={}", toolName, studentFp);
            return toolResultCompactor.compactAndCap(toolName, json);
        } catch (LmsSessionExpiredException exception) {
            log.info("chat private tool expired: tool={} studentFp={}", toolName, studentFp);
            return toolError(LMS_SESSION_EXPIRED_GUIDANCE);
        } catch (JsonProcessingException exception) {
            throw new ChatUnavailableException(exception);
        }
    }

    private String dispatchPrivateLibraryTool(
            String toolName,
            java.util.function.Supplier<Object> serviceCall
    ) {
        String sessionKey = LibraryToolContext.currentSessionKey();
        if (sessionKey == null || sessionKey.isBlank()) {
            log.info("chat private tool refused: tool={} reason=no-library-session", toolName);
            return toolError(LIBRARY_SESSION_GUIDANCE);
        }
        String sessionFp = com.ssuai.domain.library.auth.LibrarySessionStore.fingerprint(sessionKey);
        log.info("chat private tool requested: tool={} sessionFp={}", toolName, sessionFp);
        try {
            Object response = serviceCall.get();
            String json = objectMapper.writeValueAsString(response);
            log.info("chat private tool completed: tool={} sessionFp={}", toolName, sessionFp);
            return toolResultCompactor.compactAndCap(toolName, json);
        } catch (LibraryAuthRequiredException exception) {
            log.info("chat private tool auth: tool={} sessionFp={}", toolName, sessionFp);
            return toolError(LIBRARY_SESSION_GUIDANCE);
        } catch (JsonProcessingException exception) {
            throw new ChatUnavailableException(exception);
        }
    }

    private Map<String, Object> restaurantArgs(OpenAiToolCall toolCall) {
        String restaurant = optionalArgument(toolCall, "restaurant").trim();
        if (restaurant.isBlank()) {
            return Map.of();
        }
        return Map.of("restaurant", restaurant);
    }

    private String callMcp(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = clientFor(toolName);
        if (client == null) {
            return toolError("사용 가능한 MCP 클라이언트가 없습니다.");
        }
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
        } catch (RuntimeException exception) {
            log.warn("mcp tool call failed: tool={} error={}", toolName, exception.getClass().getSimpleName());
            return toolError("도구 호출에 실패했습니다.");
        }

        String text = extractText(result.content());
        if (Boolean.TRUE.equals(result.isError())) {
            return toolError(text.isBlank() ? "도구 실행에 실패했습니다." : text);
        }
        if (text.isBlank()) {
            return toolError("도구 응답이 비어 있습니다.");
        }
        return toolResultCompactor.compactAndCap(toolName, text);
    }

    private static String extractText(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (McpSchema.Content content : contents) {
            if (content instanceof McpSchema.TextContent textContent) {
                buffer.append(textContent.text());
            }
        }
        return buffer.toString();
    }

    String compactAndCap(String toolName, String rawJsonText) {
        return toolResultCompactor.compactAndCap(toolName, rawJsonText);
    }

    private String requiredArgument(OpenAiToolCall toolCall, String fieldName) {
        String value = optionalArgument(toolCall, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + ": 필수 인자입니다.");
        }
        return value;
    }

    private int requiredIntArgument(OpenAiToolCall toolCall, String fieldName) {
        JsonNode value = arguments(toolCall).get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(fieldName + ": 필수 인자입니다.");
        }
        if (value.canConvertToInt()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(fieldName + ": 정수여야 합니다.", exception);
            }
        }
        throw new IllegalArgumentException(fieldName + ": 정수여야 합니다.");
    }

    private String optionalArgument(OpenAiToolCall toolCall, String fieldName) {
        JsonNode arguments = arguments(toolCall);
        JsonNode value = arguments.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private Map<String, Object> rawArguments(OpenAiToolCall toolCall) {
        JsonNode node = arguments(toolCall);
        if (node == null || node.isEmpty()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.treeToValue(node, Map.class);
            return map == null ? Map.of() : map;
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Optional<Integer> optionalIntArgument(OpenAiToolCall toolCall, String fieldName) {
        JsonNode value = arguments(toolCall).get(fieldName);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (value.canConvertToInt()) {
            return Optional.of(value.asInt());
        }
        if (value.isTextual()) {
            String text = value.asText("").trim();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private JsonNode arguments(OpenAiToolCall toolCall) {
        String rawArguments = toolCall.function() == null ? null : toolCall.function().arguments();
        if (rawArguments == null || rawArguments.isBlank()) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(rawArguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("도구 인자를 JSON으로 해석하지 못했습니다.", exception);
        }
    }

    private String toolError(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "error", message == null || message.isBlank() ? "도구 실행에 실패했습니다." : message
            ));
        } catch (JsonProcessingException exception) {
            throw new ChatUnavailableException(exception);
        }
    }

    private static String formatModel(LlmCompletionResult result) {
        if (result == null) return null;
        String provider = result.providerName();
        String model = result.model();
        if (provider == null && model == null) return null;
        if (provider == null) return model;
        if (model == null) return provider;
        return provider + " · " + model;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new ChatUnavailableException();
        }
        return content.trim();
    }

    private static List<OpenAiToolCall> safeToolCalls(List<OpenAiToolCall> toolCalls) {
        return toolCalls == null ? List.of() : toolCalls;
    }

    /**
     * Catches messages the chatbot can't usefully serve from any of its
     * tools — 수강신청 / 개인정보 — and short-circuits
     * to {@link #SCOPE_GUIDANCE} so the LLM doesn't hallucinate. Note
     * that "성적", "시간표", "GPA" are intentionally **not** in this list:
     * those are now real tools ({@code get_my_schedule},
     * {@code get_my_grades}, {@code check_graduation_requirements}) and
     * the LLM is allowed to call them. The
     * authenticated-vs-anonymous gating happens inside
     * {@code executeToolCall} which returns {@link #SAINT_SESSION_GUIDANCE}
     * when the chat lacks a student id.
     */
    private static boolean looksLikeOutOfScopeRequest(String message) {
        String normalized = normalize(message);
        return containsAny(normalized, "수강신청", "개인정보");
    }

    private static boolean looksLikeSecretInput(String message) {
        String normalized = normalize(message);
        return containsAny(normalized, "비밀번호", "password", "쿠키", "cookie", "세션", "session",
                "api key", "apikey", "토큰", "token", "jwt");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
