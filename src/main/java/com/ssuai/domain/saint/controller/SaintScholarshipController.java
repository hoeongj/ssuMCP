package com.ssuai.domain.saint.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.domain.saint.service.SaintScholarshipService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/saint")
@Tag(name = "Saint", description = "u-SAINT realtime data API")
public class SaintScholarshipController {

    private final SaintScholarshipService scholarshipService;

    public SaintScholarshipController(SaintScholarshipService scholarshipService) {
        this.scholarshipService = scholarshipService;
    }

    @GetMapping("/scholarships")
    @Operation(summary = "Get the caller's scholarship receipt history")
    public ApiResponse<List<ScholarshipEntry>> getMyScholarships(
            HttpServletRequest request,
            @RequestParam(required = false) Integer year) {
        Object studentId = request.getAttribute(AuthAttributes.STUDENT_ID);
        if (!(studentId instanceof String id) || id.isBlank()) {
            throw new UnauthorizedException();
        }
        return ApiResponse.success(scholarshipService.fetchScholarships(id, year));
    }
}
