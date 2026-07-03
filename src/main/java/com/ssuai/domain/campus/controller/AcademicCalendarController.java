package com.ssuai.domain.campus.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;
import com.ssuai.domain.campus.dto.AcademicCalendarResponse;
import com.ssuai.domain.campus.service.AcademicCalendarService;
import com.ssuai.global.response.ApiResponse;

/**
 * Public academic-calendar lookup. Mirrors the {@code get_academic_calendar}
 * MCP tool so the ssuAI frontend can consume the same data over REST.
 */
@Validated
@RestController
@RequestMapping("/api/academic-calendar")
@Tag(name = "Academic calendar", description = "Academic calendar lookup API")
public class AcademicCalendarController {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final AcademicCalendarService academicCalendarService;

    public AcademicCalendarController(AcademicCalendarService academicCalendarService) {
        this.academicCalendarService = academicCalendarService;
    }

    @GetMapping
    @Operation(summary = "Get the academic calendar for a year (defaults to the current year)")
    public ApiResponse<AcademicCalendarResponse> getAcademicCalendar(
            @RequestParam(required = false)
            @Min(2019)
            @Max(2027)
            @Parameter(description = "Optional 4-digit year (2019-2027). Defaults to the current KST year.")
            Integer year
    ) {
        int resolvedYear = year != null ? year : LocalDate.now(SEOUL_ZONE).getYear();
        List<AcademicCalendarEvent> events = academicCalendarService.getCalendar(resolvedYear);
        return ApiResponse.success(new AcademicCalendarResponse(resolvedYear, events));
    }
}
