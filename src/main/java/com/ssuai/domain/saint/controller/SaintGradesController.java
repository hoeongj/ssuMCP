package com.ssuai.domain.saint.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.service.SaintGradesService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

/**
 * Authenticated REST entry point for cumulative u-SAINT grades (Task 16
 * PR 16c). Same access pattern as {@code SaintScheduleController}:
 * read {@code AuthAttributes.STUDENT_ID} off the request, blank → 401
 * {@code UNAUTHORIZED}, missing portal cookies → 401
 * {@code SAINT_SESSION_EXPIRED} (surfaced from the service).
 */
@RestController
@RequestMapping("/api/saint")
@Tag(name = "Saint", description = "u-SAINT realtime data API")
public class SaintGradesController {

    private final SaintGradesService gradesService;

    public SaintGradesController(SaintGradesService gradesService) {
        this.gradesService = gradesService;
    }

    @GetMapping("/grades")
    @Operation(summary = "Get the caller's cumulative u-SAINT grades")
    public ApiResponse<GradesResponse> getMyGrades(HttpServletRequest request) {
        Object studentId = request.getAttribute(AuthAttributes.STUDENT_ID);
        if (!(studentId instanceof String id) || id.isBlank()) {
            throw new UnauthorizedException();
        }
        return ApiResponse.success(gradesService.fetchGrades(id));
    }
}
