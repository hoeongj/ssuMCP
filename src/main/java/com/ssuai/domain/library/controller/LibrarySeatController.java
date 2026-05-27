package com.ssuai.domain.library.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/library")
@Tag(name = "Library", description = "Library seat status API")
public class LibrarySeatController {

    private final LibrarySeatService libraryService;

    public LibrarySeatController(LibrarySeatService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping("/seats")
    @Operation(summary = "Get current library seat availability for a floor")
    public ApiResponse<LibrarySeatStatusResponse> getSeatStatus(
            @RequestParam
            @Parameter(
                    description = "Library floor code. Allowed values: 2, 5, 6.",
                    required = true,
                    example = "2"
            )
            int floor,
            HttpServletRequest request
    ) {
        LibraryFloor target = LibraryFloor.fromCode(floor);
        String sessionKey = request.getSession().getId();
        return ApiResponse.success(libraryService.getSeatStatusForSession(target, sessionKey));
    }
}
