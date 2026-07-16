package com.ssuai.domain.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Executable inventory for every public MCP tool contract.
 *
 * <p>The entries intentionally record behavioral expectations in addition to input schemas so a
 * new tool cannot be registered without declaring authentication, ownership, idempotency, empty
 * state, and response-size semantics. Connector success/error fixtures remain in the focused test
 * class named by {@link ToolContract#coverageEvidence()}.
 */
@ActiveProfiles("test")
@SpringBootTest
class McpToolContractInventoryTests {

    private static final String NOT_APPLICABLE = "not applicable";
    private static final String PUBLIC_SESSION = "public tool; no session-owned data";
    private static final String PRIVATE_SESSION =
            "authoritative resolver: exact explicit session or same-transport valid binding; no fallback";
    private static final String OWNER_SESSION =
            PRIVATE_SESSION + "; every pending or durable object is checked against the resolved owner";

    private final ObjectMapper objectMapper;
    private final Map<String, ToolCallback> callbacks;

    @Autowired
    McpToolContractInventoryTests(
            ObjectMapper objectMapper,
            @Qualifier("ssuaiMcpTools") ToolCallbackProvider toolCallbackProvider) {
        this.objectMapper = objectMapper;
        this.callbacks = new LinkedHashMap<>();
        Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .forEach(callback -> callbacks.put(callback.getToolDefinition().name(), callback));
    }

    @Test
    void inventoryContainsEveryRegisteredToolExactlyOnce() {
        assertThat(CONTRACTS).hasSize(52);
        assertThat(CONTRACTS).extracting(ToolContract::name).doesNotHaveDuplicates();
        assertThat(CONTRACTS).extracting(ToolContract::name).containsExactlyInAnyOrderElementsOf(callbacks.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    void registeredInputSchemaMatchesInventory(ToolContract contract) throws Exception {
        ToolCallback callback = callbacks.get(contract.name());
        assertThat(callback).as(contract.name()).isNotNull();

        JsonNode schema = objectMapper.readTree(callback.getToolDefinition().inputSchema());
        Set<String> properties = fieldNames(schema.path("properties"));
        Set<String> required = values(schema.path("required"));
        Set<String> declared = new LinkedHashSet<>(contract.requiredInputs());
        declared.addAll(contract.optionalInputs());

        assertThat(properties).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(required).containsExactlyInAnyOrderElementsOf(contract.requiredInputs());
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(callback.getToolDefinition().description()).isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    void behavioralContractIsCompleteAndHasExecutableCoverage(ToolContract contract) throws Exception {
        assertThat(contract.normalResponseCode()).isNotBlank();
        assertThat(contract.responseSchema()).isNotBlank();
        assertThat(contract.validationBehavior()).isNotBlank();
        assertThat(contract.emptyStateBehavior()).isNotBlank();
        assertThat(contract.sessionIsolationBehavior()).isNotBlank();
        assertThat(contract.sideEffects()).isNotBlank();
        assertThat(contract.idempotency()).isNotBlank();
        assertThat(contract.maximumResponseSizeBehavior()).isNotBlank();
        assertThat(Class.forName(contract.coverageEvidence())).isNotNull();

        if (contract.authentication() == Authentication.PRIVATE) {
            assertThat(contract.optionalInputs()).contains("mcp_session_id");
            assertThat(contract.requiredInputs()).doesNotContain("mcp_session_id");
            assertThat(contract.sessionIsolationBehavior()).contains("no fallback");
        }
        if (contract.ownershipRequired()) {
            assertThat(contract.authentication()).isNotEqualTo(Authentication.PUBLIC);
            assertThat(contract.sessionIsolationBehavior()).contains("owner");
        }
        if (contract.operation() == Operation.READ) {
            assertThat(contract.sideEffects()).isEqualTo("none");
            assertThat(contract.idempotency()).contains("safe");
        }
    }

    private static Stream<ToolContract> contracts() {
        return CONTRACTS.stream();
    }

    private static Set<String> fieldNames(JsonNode objectNode) {
        Set<String> names = new LinkedHashSet<>();
        objectNode.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> values(JsonNode arrayNode) {
        Set<String> values = new LinkedHashSet<>();
        StreamSupport.stream(arrayNode.spliterator(), false).map(JsonNode::asText).forEach(values::add);
        return values;
    }

    private static Set<String> inputs(String... names) {
        return Set.of(names);
    }

    private static ToolContract publicRead(
            String name,
            Provider provider,
            Set<String> required,
            Set<String> optional,
            String schema,
            String validation,
            String emptyState,
            String responseSize,
            String evidence) {
        return new ToolContract(
                name,
                Authentication.PUBLIC,
                provider,
                Operation.READ,
                required,
                optional,
                "DIRECT_DATA",
                schema,
                validation,
                emptyState,
                PUBLIC_SESSION,
                "none",
                "read-only; safe to retry",
                responseSize,
                false,
                evidence);
    }

    private static ToolContract privateRead(
            String name,
            Provider provider,
            Set<String> required,
            Set<String> optional,
            String schema,
            String validation,
            String emptyState,
            String responseSize,
            String evidence) {
        return privateRead(name, provider, required, optional, schema, validation, emptyState, responseSize, false,
                evidence);
    }

    private static ToolContract privateOwnedRead(
            String name,
            Provider provider,
            Set<String> required,
            Set<String> optional,
            String schema,
            String validation,
            String emptyState,
            String responseSize,
            String evidence) {
        return privateRead(name, provider, required, optional, schema, validation, emptyState, responseSize, true,
                evidence);
    }

    private static ToolContract privateRead(
            String name,
            Provider provider,
            Set<String> required,
            Set<String> optional,
            String schema,
            String validation,
            String emptyState,
            String responseSize,
            boolean ownershipRequired,
            String evidence) {
        Set<String> optionalWithSession = new LinkedHashSet<>(optional);
        optionalWithSession.add("mcp_session_id");
        return new ToolContract(
                name,
                Authentication.PRIVATE,
                provider,
                Operation.READ,
                required,
                Set.copyOf(optionalWithSession),
                "OK",
                "McpPrivateToolResponse<" + schema + ">",
                validation,
                emptyState,
                ownershipRequired ? OWNER_SESSION : PRIVATE_SESSION,
                "none",
                "read-only; safe to retry",
                responseSize,
                ownershipRequired,
                evidence);
    }

    private static ToolContract privateWrite(
            String name,
            Provider provider,
            Operation operation,
            Set<String> required,
            Set<String> optional,
            String schema,
            String validation,
            String emptyState,
            String sideEffects,
            String idempotency,
            String evidence) {
        Set<String> optionalWithSession = new LinkedHashSet<>(optional);
        optionalWithSession.add("mcp_session_id");
        return new ToolContract(
                name,
                Authentication.PRIVATE,
                provider,
                operation,
                required,
                Set.copyOf(optionalWithSession),
                "OK",
                "McpPrivateToolResponse<" + schema + ">",
                validation,
                emptyState,
                OWNER_SESSION,
                sideEffects,
                idempotency,
                "single action/result envelope; no unbounded collection",
                true,
                evidence);
    }

    private static final List<ToolContract> CONTRACTS = List.of(
            publicRead(
                    "get_today_meal", Provider.MEAL, inputs(), inputs("restaurant"), "MealResponse",
                    "unknown restaurant -> VALIDATION_ERROR", "OK with an empty meals list", "fixed restaurant set",
                    "com.ssuai.domain.mcp.tool.MealMcpToolsTests"),
            publicRead(
                    "get_meal_by_date", Provider.MEAL, inputs("date"), inputs("restaurant"), "MealResponse",
                    "missing/non-ISO date or unknown restaurant -> VALIDATION_ERROR",
                    "OK with an empty meals list", "fixed restaurant set",
                    "com.ssuai.domain.mcp.tool.MealMcpToolsTests"),
            publicRead(
                    "get_meal_weekly", Provider.MEAL, inputs(), inputs("weekOffset"), "WeeklyMealResponse",
                    "week offset is bounded to the documented window", "OK with seven empty day entries",
                    "one bounded week", "com.ssuai.domain.mcp.tool.MealMcpToolsTests"),
            publicRead(
                    "get_dorm_weekly_meal", Provider.MEAL, inputs(), inputs(), "DormWeeklyMealResponse",
                    "source classification errors -> PARSER_ERROR", "OK with empty meal/notice collections",
                    "one bounded week", "com.ssuai.domain.mcp.tool.DormMcpToolsTests"),
            publicRead(
                    "search_campus_facilities", Provider.CAMPUS, inputs(), inputs("query", "page", "size"),
                    "CampusFacilitySearchResponse", "query length/page/size -> VALIDATION_ERROR",
                    "OK with empty facilities", "page size <= 50",
                    "com.ssuai.domain.mcp.tool.CampusMcpToolsTests"),
            publicRead(
                    "get_academic_calendar", Provider.CALENDAR, inputs(), inputs("year"),
                    "AcademicCalendarResponse", "unsupported year -> VALIDATION_ERROR", "OK with empty events",
                    "one academic year", "com.ssuai.domain.mcp.tool.CampusMcpToolsTests"),
            publicRead(
                    "find_academic_calendar_events", Provider.CALENDAR, inputs(),
                    inputs("year", "month", "keyword", "limit"), "AcademicCalendarSearchResponse",
                    "month/limit bounds -> VALIDATION_ERROR", "OK with empty events", "limit <= 50",
                    "com.ssuai.domain.mcp.tool.CampusMcpToolsTests"),
            new ToolContract(
                    "get_auth_status", Authentication.LIFECYCLE, Provider.AUTH, Operation.READ, inputs(),
                    inputs("mcp_session_id"), "OK|NO_SESSION|INVALID_SESSION|SESSION_MISMATCH",
                    "McpAuthStatusResponse", "invalid explicit id is INVALID_SESSION without fallback",
                    "NO_SESSION with all providers unlinked", PRIVATE_SESSION, "none", "read-only; safe to retry",
                    "three provider status entries", false, "com.ssuai.domain.mcp.tool.McpAuthMcpToolsTests"),
            new ToolContract(
                    "start_auth", Authentication.LIFECYCLE, Provider.AUTH, Operation.AUTH_MUTATION,
                    inputs("provider"), inputs("mcp_session_id"), "LOGIN_STARTED",
                    "McpAuthStartResponse", "unknown provider -> VALIDATION_ERROR; invalid explicit id -> INVALID_SESSION",
                    "new session is created only when the id is omitted", "explicit id is exact; authorized rebind only",
                    "creates auth state and may create/bind a session", "a repeated omitted-id call creates a new session",
                    "single login URL; capability values are never logged", false,
                    "com.ssuai.domain.mcp.tool.McpAuthMcpToolsTests"),
            new ToolContract(
                    "logout_provider", Authentication.LIFECYCLE, Provider.AUTH, Operation.AUTH_MUTATION,
                    inputs("provider"), inputs("mcp_session_id"), "OK",
                    "McpAuthLogoutResponse", "unknown provider -> VALIDATION_ERROR; bad id -> INVALID_SESSION",
                    "already absent provider produces a safe no-op result", OWNER_SESSION,
                    "atomically unlinks one provider, deletes credentials, and unbinds matching transport",
                    "revocation is effect-idempotent", "single response", true,
                    "com.ssuai.domain.mcp.tool.McpAuthMcpToolsTests"),
            new ToolContract(
                    "logout_all", Authentication.LIFECYCLE, Provider.AUTH, Operation.AUTH_MUTATION, inputs(),
                    inputs("mcp_session_id"), "OK", "McpAuthLogoutResponse",
                    "bad explicit id -> INVALID_SESSION; mismatch -> SESSION_MISMATCH",
                    "missing transport binding -> NO_SESSION", OWNER_SESSION,
                    "atomically invalidates the session, deletes all provider credentials, and unbinds transport",
                    "revocation is effect-idempotent", "single response", true,
                    "com.ssuai.domain.mcp.tool.McpAuthMcpToolsTests"),
            publicRead(
                    "get_library_seat_status", Provider.LIBRARY, inputs("floor"), inputs("compact"),
                    "LibrarySeatStatusResponse", "unsupported floor -> VALIDATION_ERROR",
                    "OK with zero reconciled counts", "compact or bounded zones",
                    "com.ssuai.domain.mcp.tool.LibrarySeatMcpToolTests"),
            publicRead(
                    "get_library_seat_catalog", Provider.LIBRARY, inputs(),
                    inputs("floor_code", "room_code", "include_layout"), "LibrarySeatCatalogResponse",
                    "unknown floor/room -> VALIDATION_ERROR", "OK with empty catalog selection",
                    "layout is opt-in; static bounded catalog",
                    "com.ssuai.domain.mcp.tool.LibrarySeatCatalogMcpToolTests"),
            privateRead(
                    "recommend_library_seats", Provider.LIBRARY, inputs("floor"),
                    inputs("window", "outlet", "standing", "edge", "quiet", "near_entrance",
                            "include_graduate_only", "limit"),
                    "LibrarySeatRecommendationResponse", "floor/limit/preferences -> VALIDATION_ERROR",
                    "OK with empty recommendations plus coverage warnings", "limit <= 10",
                    "com.ssuai.domain.mcp.tool.LibrarySeatRecommendationMcpToolTests"),
            privateRead(
                    "get_library_available_seats", Provider.LIBRARY, inputs(), inputs("compact", "offset", "limit"),
                    "LibraryAllAvailableSeatsResponse", "offset/limit -> VALIDATION_ERROR",
                    "OK with empty rooms and reconciled totals", "compact mode or per-room limit <= 200 with truncation metadata",
                    "com.ssuai.domain.library.service.LibraryAvailableSeatsServiceTests"),
            privateRead(
                    "get_room_available_seats", Provider.LIBRARY, inputs("roomId"),
                    inputs("compact", "offset", "limit"), "LibraryRoomAvailableSeatsResponse",
                    "unknown room/offset/limit -> VALIDATION_ERROR", "OK with empty seats and reconciled totals",
                    "compact mode or limit <= 200 with truncation metadata",
                    "com.ssuai.domain.library.service.LibraryAvailableSeatsServiceTests"),
            publicRead(
                    "search_library_book", Provider.LIBRARY, inputs("query"), inputs("page", "size"),
                    "LibraryBookSearchResponse", "query, page, and size are independently validated",
                    "OK with total=0, items=[], hasNext=false", "size <= 20",
                    "com.ssuai.domain.mcp.tool.LibraryBookMcpToolTests"),
            privateRead(
                    "get_my_library_loans", Provider.LIBRARY, inputs(), inputs(), "LibraryLoansResponse",
                    "provider/session failures use the common private error taxonomy", "OK with an empty loans list",
                    "account-scoped bounded loans", "com.ssuai.domain.mcp.tool.LibraryLoansMcpToolTests"),
            privateWrite(
                    "prepare_reserve_library_seat", Provider.LIBRARY, Operation.PREPARE, inputs("seat_id"), inputs(),
                    "LibraryPrepareResult", "non-positive/non-numeric seat -> VALIDATION_ERROR",
                    "existing reservation -> ACTION_CONFLICT", "creates an owner-scoped pending action only",
                    "same owner/type/target supersedes deterministically; no upstream reservation",
                    "com.ssuai.domain.mcp.tool.LibraryReservationMcpToolTests"),
            privateWrite(
                    "prepare_cancel_library_seat", Provider.LIBRARY, Operation.PREPARE, inputs(), inputs(),
                    "LibraryPrepareResult", "session/provider failures use the common private error taxonomy",
                    "NO_CURRENT_SEAT with actionId=null", "creates an owner-scoped pending action only when a seat exists",
                    "same owner/type/target supersedes deterministically; no upstream cancellation",
                    "com.ssuai.domain.mcp.tool.LibraryCancelMcpToolTests"),
            privateRead(
                    "get_my_library_seat", Provider.LIBRARY, inputs(), inputs(), "LibraryCurrentSeatResponse",
                    "provider/session failures use the common private error taxonomy", "NO_CURRENT_SEAT",
                    "single current seat", "com.ssuai.domain.mcp.tool.LibraryCurrentSeatMcpToolTests"),
            privateWrite(
                    "prepare_swap_library_seat", Provider.LIBRARY, Operation.PREPARE, inputs("new_seat_id"), inputs(),
                    "LibraryPrepareResult", "non-positive/non-numeric seat -> VALIDATION_ERROR",
                    "NO_CURRENT_SEAT with actionId=null", "creates an owner-scoped pending action only when a seat exists",
                    "same current charge supersedes deterministically; no upstream write",
                    "com.ssuai.domain.mcp.tool.LibrarySwapMcpToolTests"),
            privateWrite(
                    "wait_for_library_seat", Provider.LIBRARY, Operation.WRITE, inputs(),
                    inputs("floor", "room_ids", "seat_attributes", "target_seat_id", "expires_in_minutes"),
                    "LibraryWaitIntentResponse", "malformed preferences/expiry -> VALIDATION_ERROR",
                    "invalid empty preference set -> VALIDATION_ERROR", "registers an owner-scoped durable wait intent",
                    "returns the existing active owner intent on retry",
                    "com.ssuai.domain.mcp.tool.LibraryWaitMcpToolTests"),
            privateOwnedRead(
                    "get_library_wait_status", Provider.LIBRARY, inputs(), inputs("intent_id"),
                    "LibraryWaitIntentResponse", "invalid intent id -> VALIDATION_ERROR",
                    "NO_PENDING_ACTION without revealing foreign intent existence", "single intent",
                    "com.ssuai.domain.mcp.tool.LibraryWaitMcpToolTests"),
            privateWrite(
                    "cancel_library_wait", Provider.LIBRARY, Operation.WRITE, inputs(), inputs(),
                    "LibraryWaitIntentResponse", "session/provider failures use the common private error taxonomy",
                    "NO_PENDING_ACTION", "cancels only the resolved owner's cancellable active intent",
                    "retries do not cancel another owner or a terminal intent",
                    "com.ssuai.domain.mcp.tool.LibraryWaitMcpToolTests"),
            privateWrite(
                    "confirm_action", Provider.LIBRARY, Operation.CONFIRM, inputs(), inputs("action_id"), "String",
                    "ambiguous/missing action -> NO_PENDING_ACTION or ACTION_CONFLICT", "NO_PENDING_ACTION",
                    "under a credential-version fence, claims and executes only an owner-scoped action",
                    "terminal action retries return the recorded outcome and never repeat the upstream write",
                    "com.ssuai.domain.mcp.tool.ConfirmActionMcpToolTests"),
            privateRead(
                    "get_my_schedule", Provider.SAINT, inputs(), inputs("year", "term"), "ScheduleResponse",
                    "year/term pair and range -> VALIDATION_ERROR", "OK with meetings=[]",
                    "bounded semester schedule", "com.ssuai.domain.mcp.tool.SaintScheduleMcpToolTests"),
            privateRead(
                    "get_my_grades", Provider.SAINT, inputs(), inputs(), "GradesResponse",
                    "provider/session failures use the common private error taxonomy", "OK with semesters=[]",
                    "account academic history", "com.ssuai.domain.mcp.tool.SaintGradesMcpToolTests"),
            privateRead(
                    "get_my_chapel_info", Provider.SAINT, inputs(), inputs("year", "semester"), "ChapelInfo",
                    "year/semester -> VALIDATION_ERROR", "OK with explicit unknown/empty upstream state",
                    "single requested term", "com.ssuai.domain.mcp.tool.SaintExtendedMcpToolsTests"),
            privateRead(
                    "check_graduation_requirements", Provider.SAINT, inputs(), inputs(), "GraduationStatus",
                    "provider/session failures use the common private error taxonomy", "OK with UNKNOWN gate states",
                    "single graduation evaluation", "com.ssuai.domain.mcp.tool.SaintExtendedMcpToolsTests"),
            privateRead(
                    "get_my_scholarships", Provider.SAINT, inputs(), inputs("year"), "ScholarshipHistory",
                    "year -> VALIDATION_ERROR", "OK with scholarships=[]", "account history; optional year filter",
                    "com.ssuai.domain.mcp.tool.SaintExtendedMcpToolsTests"),
            privateRead(
                    "simulate_gpa", Provider.SAINT, inputs("plannedCredits"),
                    inputs("plannedGradePointAverage", "targetGpa"), "GpaSimulationResponse",
                    "non-finite, negative, or out-of-range values -> VALIDATION_ERROR",
                    "planned-only and target-only states are explicit", "single deterministic calculation",
                    "com.ssuai.domain.mcp.tool.SaintExtendedMcpToolsTests"),
            privateRead(
                    "get_my_assignments", Provider.LMS, inputs(), inputs("compact", "term_id"),
                    "LmsAssignmentsResponse", "term id -> VALIDATION_ERROR", "OK with assignments=[]",
                    "compact mode; bounded selected term", "com.ssuai.domain.mcp.tool.LmsAssignmentsMcpToolTests"),
            privateRead(
                    "get_my_lms_terms", Provider.LMS, inputs(), inputs(), "LmsTermsResponse",
                    "provider/session failures use the common private error taxonomy", "OK with terms=[]",
                    "bounded provider term list", "com.ssuai.domain.mcp.tool.LmsAssignmentsMcpToolTests"),
            privateRead(
                    "get_lms_dashboard", Provider.LMS, inputs(), inputs("term_id"), "LmsDashboardResponse",
                    "term id -> VALIDATION_ERROR", "OK with empty courses/deadlines",
                    "bounded selected term", "com.ssuai.domain.mcp.tool.LmsDashboardMcpToolTests"),
            privateRead(
                    "get_my_lms_courses", Provider.LMS, inputs(), inputs("term_id"), "LmsCoursesResponse",
                    "term id -> VALIDATION_ERROR", "OK with courses=[]", "bounded selected term",
                    "com.ssuai.domain.mcp.tool.LmsMaterialsMcpToolTests"),
            privateRead(
                    "get_my_lms_materials", Provider.LMS, inputs("course_ids"), inputs("term_id"),
                    "LmsMaterialsResponse", "empty/invalid course ids -> VALIDATION_ERROR", "OK with materials=[]",
                    "bounded caller-selected course set", "com.ssuai.domain.mcp.tool.LmsMaterialsMcpToolTests"),
            privateWrite(
                    "prepare_lms_material_export", Provider.LMS, Operation.PREPARE, inputs("content_ids"),
                    inputs("term_id"), "LmsExportPrepareResponse", "empty/invalid content ids -> VALIDATION_ERROR",
                    "OK with explicitly excluded unsupported materials", "creates a versioned owner-scoped preview only",
                    "same owner/request is deduplicated or superseded without creating a download capability",
                    "com.ssuai.domain.mcp.tool.LmsMaterialExportMcpToolTests"),
            privateWrite(
                    "confirm_lms_material_export", Provider.LMS, Operation.CONFIRM, inputs(), inputs("action_id"),
                    "LmsExportConfirmResponse", "foreign/ambiguous action -> NO_PENDING_ACTION or ACTION_CONFLICT",
                    "NO_PENDING_ACTION", "consumes exactly one owner preview and creates owner-scoped job/capability",
                    "replay returns the same job outcome without issuing a second capability",
                    "com.ssuai.domain.mcp.tool.LmsMaterialExportMcpToolTests"),
            privateWrite(
                    "export_all_lms_materials", Provider.LMS, Operation.PREPARE, inputs(), inputs("term_id"),
                    "LmsExportPrepareResponse", "term id -> VALIDATION_ERROR", "OK with an empty preview",
                    "creates a versioned owner-scoped all-material preview only",
                    "same owner/term preview is deduplicated or superseded",
                    "com.ssuai.domain.mcp.tool.LmsMaterialExportMcpToolTests"),
            publicRead(
                    "get_recent_notices", Provider.NOTICE, inputs(), inputs("category", "page", "compact"),
                    "NoticePageResponse", "category/page -> VALIDATION_ERROR", "OK with notices=[]",
                    "paged; compact mode", "com.ssuai.domain.mcp.tool.NoticeMcpToolsTests"),
            publicRead(
                    "search_notices", Provider.NOTICE, inputs("keyword"), inputs("category", "page", "compact"),
                    "NoticePageResponse", "keyword/category/page -> VALIDATION_ERROR", "OK with notices=[]",
                    "paged; compact mode", "com.ssuai.domain.mcp.tool.NoticeMcpToolsTests"),
            publicRead(
                    "list_notice_categories", Provider.NOTICE, inputs(), inputs(), "NoticeCategoryResponse",
                    "no user input", "OK with categories=[] only when source configuration is empty",
                    "fixed category list", "com.ssuai.domain.mcp.tool.NoticeMcpToolsTests"),
            publicRead(
                    "get_notice_detail", Provider.NOTICE, inputs("url"), inputs(), "NoticeDetailResponse",
                    "non-official/malformed URL -> VALIDATION_ERROR", "NOT_FOUND or explicit incomplete body metadata",
                    "one notice plus bounded attachments", "com.ssuai.domain.mcp.tool.NoticeMcpToolsTests"),
            publicRead(
                    "get_active_notices", Provider.NOTICE, inputs(), inputs("category"), "ActiveNoticeResponse",
                    "unknown category -> VALIDATION_ERROR", "OK with notices=[]", "bounded active notice set",
                    "com.ssuai.domain.mcp.tool.NoticeMcpToolsTests"),
            publicRead(
                    "get_department_notices", Provider.NOTICE, inputs("department"), inputs("page"),
                    "DepartmentNoticeResponse", "blank department/page -> VALIDATION_ERROR", "OK with notices=[]",
                    "paged", "com.ssuai.domain.mcp.tool.NoticeMcpToolsTests"),
            publicRead(
                    "classify_academic_question", Provider.POLICY, inputs("query"), inputs(),
                    "AcademicQuestionClassification", "blank/oversized query -> VALIDATION_ERROR",
                    "UNKNOWN/general classification with confidence", "single classification",
                    "com.ssuai.domain.mcp.tool.AcademicPolicyMcpToolsTests"),
            publicRead(
                    "search_academic_policy_sources", Provider.POLICY, inputs("query"),
                    inputs("category", "limit", "live"), "AcademicPolicySearchResponse",
                    "query/category/limit -> VALIDATION_ERROR", "OK with evidence=[] and explicit provenance",
                    "limit <= 10", "com.ssuai.domain.mcp.tool.AcademicPolicyMcpToolsTests"),
            publicRead(
                    "get_academic_policy_brief", Provider.POLICY, inputs("query"),
                    inputs("category", "limit", "live"), "AcademicPolicyBriefResponse",
                    "query/category/limit -> VALIDATION_ERROR", "OK with unresolved facts and no fabricated decision",
                    "limit <= 10 evidence items", "com.ssuai.domain.mcp.tool.AcademicPolicyMcpToolsTests"),
            publicRead(
                    "check_scholarship_policy", Provider.POLICY, inputs(),
                    inputs("query", "gpa", "earnedCredits", "admissionYear", "topikLevel",
                            "internationalStudent", "live", "limit"),
                    "ScholarshipPolicyDecision", "invalid ranges or no usable question/context -> VALIDATION_ERROR",
                    "INSUFFICIENT_EVIDENCE with unknownRequirements", "limit <= 10 evidence items",
                    "com.ssuai.domain.mcp.tool.AcademicPolicyMcpToolsTests"),
            publicRead(
                    "list_academic_policy_sources", Provider.POLICY, inputs(), inputs("category", "live"),
                    "AcademicPolicySourceListResponse", "category -> VALIDATION_ERROR", "OK with sources=[]",
                    "bounded corpus source metadata", "com.ssuai.domain.mcp.tool.AcademicPolicyMcpToolsTests"),
            privateRead(
                    "evaluate_graduation_with_policy", Provider.SAINT, inputs(), inputs("question", "live"),
                    "GraduationPolicyEvaluation", "question/live -> VALIDATION_ERROR",
                    "OK with unresolved conditions and explicit evidence gaps", "limit-bounded evidence synthesis",
                    "com.ssuai.domain.mcp.tool.AcademicPolicyMcpToolsTests"));

    enum Authentication {
        PUBLIC,
        LIFECYCLE,
        PRIVATE
    }

    enum Provider {
        MEAL,
        CAMPUS,
        CALENDAR,
        AUTH,
        LIBRARY,
        SAINT,
        LMS,
        NOTICE,
        POLICY
    }

    enum Operation {
        READ,
        AUTH_MUTATION,
        PREPARE,
        CONFIRM,
        WRITE
    }

    record ToolContract(
            String name,
            Authentication authentication,
            Provider provider,
            Operation operation,
            Set<String> requiredInputs,
            Set<String> optionalInputs,
            String normalResponseCode,
            String responseSchema,
            String validationBehavior,
            String emptyStateBehavior,
            String sessionIsolationBehavior,
            String sideEffects,
            String idempotency,
            String maximumResponseSizeBehavior,
            boolean ownershipRequired,
            String coverageEvidence) {
        @Override
        public String toString() {
            return name;
        }
    }
}
