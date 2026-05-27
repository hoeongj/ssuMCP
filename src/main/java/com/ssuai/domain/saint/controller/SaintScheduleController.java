package com.ssuai.domain.saint.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.service.SaintScheduleService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

/**
 * Authenticated REST entry point for the cumulative u-SAINT timetable
 * (Task 16 PR 16b). Reads the caller's ssuAI student id off the request
 * attributes populated by {@code JwtAuthFilter} — Spring Security is
 * intentionally not in play (ADR 0014).
 *
 * <p>A missing or blank student-id attribute returns 401
 * {@code UNAUTHORIZED} (no access JWT). A present student id without an
 * active portal-cookie entry surfaces from the service as 401
 * {@code SAINT_SESSION_EXPIRED}, prompting the frontend to re-run the
 * SmartID SSO loop.
 */
@RestController
@RequestMapping("/api/saint")
@Tag(name = "Saint", description = "u-SAINT realtime data API")
public class SaintScheduleController {

    private final SaintScheduleService scheduleService;

    public SaintScheduleController(SaintScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/schedule")
    @Operation(summary = "Get the caller's cumulative u-SAINT timetable")
    public ApiResponse<ScheduleResponse> getMySchedule(HttpServletRequest request) {
        Object studentId = request.getAttribute(AuthAttributes.STUDENT_ID);
        if (!(studentId instanceof String id) || id.isBlank()) {
            throw new UnauthorizedException();
        }
        return ApiResponse.success(scheduleService.fetchSchedule(id));
    }
}
