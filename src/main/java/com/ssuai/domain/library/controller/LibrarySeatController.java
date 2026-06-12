package com.ssuai.domain.library.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.events.LibrarySeatSseRegistry;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/library")
@Tag(name = "Library", description = "Library seat status API")
public class LibrarySeatController {

    private final LibrarySeatService libraryService;
    private final LibrarySeatSseRegistry sseRegistry;

    public LibrarySeatController(
            LibrarySeatService libraryService,
            LibrarySeatSseRegistry sseRegistry) {
        this.libraryService = libraryService;
        this.sseRegistry = sseRegistry;
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

    @GetMapping(value = "/seats/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to live library seat update events for a floor")
    public SseEmitter streamSeatEvents(
            @RequestParam
            @Parameter(
                    description = "Library floor code. Allowed values: 2, 5, 6.",
                    required = true,
                    example = "2"
            )
            int floor,
            HttpServletResponse response
    ) {
        response.setHeader("X-Accel-Buffering", "no");
        LibraryFloor.fromCode(floor); // Validate floor code
        return sseRegistry.createEmitter(floor);
    }
}

