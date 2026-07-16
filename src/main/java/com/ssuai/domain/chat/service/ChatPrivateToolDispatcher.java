package com.ssuai.domain.chat.service;

import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.mcp.LibraryToolContext;
import com.ssuai.domain.library.service.LibraryLoansService;
import com.ssuai.domain.lms.service.LmsAssignmentsService;
import com.ssuai.domain.saint.service.SaintChapelService;
import com.ssuai.domain.saint.service.SaintGpaSimulationService;
import com.ssuai.domain.saint.service.SaintGraduationService;
import com.ssuai.domain.saint.service.SaintGradesService;
import com.ssuai.domain.saint.service.SaintScheduleService;
import com.ssuai.domain.saint.service.SaintScholarshipService;
import com.ssuai.global.exception.ChatUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.global.exception.SaintSessionExpiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "llm")
public class ChatPrivateToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LlmChatService.class);

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

    private final SaintScheduleService scheduleService;
    private final SaintGradesService gradesService;
    private final SaintChapelService chapelService;
    private final SaintGraduationService graduationService;
    private final SaintScholarshipService scholarshipService;
    private final SaintGpaSimulationService gpaSimulationService;
    private final LmsAssignmentsService lmsAssignmentsService;
    private final LibraryLoansService libraryLoansService;

    public ChatPrivateToolDispatcher(
            SaintScheduleService scheduleService,
            SaintGradesService gradesService,
            SaintChapelService chapelService,
            SaintGraduationService graduationService,
            SaintScholarshipService scholarshipService,
            SaintGpaSimulationService gpaSimulationService,
            LmsAssignmentsService lmsAssignmentsService,
            LibraryLoansService libraryLoansService
    ) {
        this.scheduleService = scheduleService;
        this.gradesService = gradesService;
        this.chapelService = chapelService;
        this.graduationService = graduationService;
        this.scholarshipService = scholarshipService;
        this.gpaSimulationService = gpaSimulationService;
        this.lmsAssignmentsService = lmsAssignmentsService;
        this.libraryLoansService = libraryLoansService;
    }

    String dispatchSchedule(
            String toolName,
            String studentId,
            Integer year,
            Integer term,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateSaintTool(
                toolName, studentId, () -> scheduleService.fetchSchedule(studentId, year, term),
                objectMapper, toolResultCompactor, toolError);
    }

    String dispatchGrades(
            String toolName,
            String studentId,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateSaintTool(
                toolName, studentId, () -> gradesService.fetchGrades(studentId),
                objectMapper, toolResultCompactor, toolError);
    }

    String dispatchChapel(
            String toolName,
            String studentId,
            Integer year,
            String semester,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateSaintTool(
                toolName, studentId, () -> chapelService.fetchChapelInfo(studentId, year, semester),
                objectMapper, toolResultCompactor, toolError);
    }

    String dispatchGraduationRequirements(
            String toolName,
            String studentId,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateSaintTool(
                toolName, studentId, () -> graduationService.fetchGraduationRequirements(studentId),
                objectMapper, toolResultCompactor, toolError);
    }

    String dispatchScholarships(
            String toolName,
            String studentId,
            Integer year,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateSaintTool(
                toolName, studentId, () -> scholarshipService.fetchScholarships(studentId, year),
                objectMapper, toolResultCompactor, toolError);
    }

    String dispatchGpaSimulation(
            String toolName,
            String studentId,
            double plannedCredits,
            Double plannedAverage,
            Double targetGpa,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateSaintTool(
                toolName, studentId,
                () -> gpaSimulationService.simulate(
                        studentId, plannedCredits, plannedAverage, targetGpa),
                objectMapper, toolResultCompactor, toolError);
    }

    String dispatchAssignments(
            String toolName,
            String studentId,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateLmsTool(
                toolName, studentId, () -> lmsAssignmentsService.fetchAssignments(studentId, null),
                objectMapper, toolResultCompactor, toolError);
    }

    String dispatchLibraryLoans(
            String toolName,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        return dispatchPrivateLibraryTool(
                toolName, () -> libraryLoansService.getLoansForSession(
                        LibraryToolContext.currentSessionKey()),
                objectMapper, toolResultCompactor, toolError);
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
            Supplier<Object> serviceCall,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        if (studentId == null || studentId.isBlank()) {
            log.info("chat private tool refused: tool={} reason=unauthenticated", toolName);
            return toolError.apply(SAINT_SESSION_GUIDANCE);
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
            return toolError.apply(SAINT_SESSION_EXPIRED_GUIDANCE);
        } catch (JsonProcessingException exception) {
            throw new ChatUnavailableException(exception);
        }
    }

    private String dispatchPrivateLmsTool(
            String toolName,
            String studentId,
            Supplier<Object> serviceCall,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        if (studentId == null || studentId.isBlank()) {
            log.info("chat private tool refused: tool={} reason=unauthenticated", toolName);
            return toolError.apply(LMS_SESSION_GUIDANCE);
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
            return toolError.apply(LMS_SESSION_EXPIRED_GUIDANCE);
        } catch (JsonProcessingException exception) {
            throw new ChatUnavailableException(exception);
        }
    }

    private String dispatchPrivateLibraryTool(
            String toolName,
            Supplier<Object> serviceCall,
            ObjectMapper objectMapper,
            ToolResultCompactor toolResultCompactor,
            Function<String, String> toolError
    ) {
        String sessionKey = LibraryToolContext.currentSessionKey();
        if (sessionKey == null || sessionKey.isBlank()) {
            log.info("chat private tool refused: tool={} reason=no-library-session", toolName);
            return toolError.apply(LIBRARY_SESSION_GUIDANCE);
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
            return toolError.apply(LIBRARY_SESSION_GUIDANCE);
        } catch (JsonProcessingException exception) {
            throw new ChatUnavailableException(exception);
        }
    }
}
