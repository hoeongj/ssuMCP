package com.ssuai.domain.saint.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.service.SaintChapelService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/saint")
@Tag(name = "Saint", description = "u-SAINT realtime data API")
public class SaintChapelController {

    private final SaintChapelService chapelService;

    public SaintChapelController(SaintChapelService chapelService) {
        this.chapelService = chapelService;
    }

    @GetMapping("/chapel")
    @Operation(summary = "Get the caller's chapel attendance information")
    public ApiResponse<ChapelInfo> getMyChapelInfo(
            HttpServletRequest request,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String semester) {
        Object studentId = request.getAttribute(AuthAttributes.STUDENT_ID);
        if (!(studentId instanceof String id) || id.isBlank()) {
            throw new UnauthorizedException();
        }
        return ApiResponse.success(chapelService.fetchChapelInfo(id, year, semester));
    }
}
