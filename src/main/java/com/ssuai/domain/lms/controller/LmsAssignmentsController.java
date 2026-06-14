package com.ssuai.domain.lms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.service.LmsAssignmentsService;
import com.ssuai.global.auth.AuthUser;
import com.ssuai.global.response.ApiResponse;

/**
 * Authenticated REST entry point for pending LMS assignments.
 *
 * <p>Reads the caller's ssuAI student id off the request attributes set by
 * {@code JwtAuthFilter}. Missing or blank student-id → 401 UNAUTHORIZED.
 * Expired LMS session cookies → 401 LMS_SESSION_EXPIRED (propagated from
 * service layer).
 */
@RestController
@RequestMapping("/api/lms")
@Tag(name = "LMS", description = "LMS (canvas.ssu.ac.kr) realtime data API")
public class LmsAssignmentsController {

    private final LmsAssignmentsService assignmentsService;

    public LmsAssignmentsController(LmsAssignmentsService assignmentsService) {
        this.assignmentsService = assignmentsService;
    }

    @GetMapping("/assignments")
    @Operation(summary = "Get the caller's pending LMS assignments and quizzes for the current term")
    public ApiResponse<AssignmentsResponse> getMyAssignments(@AuthUser String studentId) {
        return ApiResponse.success(assignmentsService.fetchAssignments(studentId, null));
    }
}
