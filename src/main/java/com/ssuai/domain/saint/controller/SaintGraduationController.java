package com.ssuai.domain.saint.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.service.SaintGraduationService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/saint")
@Tag(name = "Saint", description = "u-SAINT realtime data API")
public class SaintGraduationController {

    private final SaintGraduationService graduationService;

    public SaintGraduationController(SaintGraduationService graduationService) {
        this.graduationService = graduationService;
    }

    @GetMapping("/graduation")
    @Operation(summary = "Get the caller's graduation requirement status")
    public ApiResponse<GraduationStatus> getMyGraduationRequirements(HttpServletRequest request) {
        Object studentId = request.getAttribute(AuthAttributes.STUDENT_ID);
        if (!(studentId instanceof String id) || id.isBlank()) {
            throw new UnauthorizedException();
        }
        return ApiResponse.success(graduationService.fetchGraduationRequirements(id));
    }
}
