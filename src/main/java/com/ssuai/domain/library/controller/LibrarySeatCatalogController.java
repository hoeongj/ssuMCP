package com.ssuai.domain.library.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/library")
@Tag(name = "Library", description = "Library seat catalog API")
public class LibrarySeatCatalogController {

    private final LibrarySeatRoomCatalogService roomCatalogService;

    public LibrarySeatCatalogController(LibrarySeatRoomCatalogService roomCatalogService) {
        this.roomCatalogService = roomCatalogService;
    }

    @GetMapping("/seat-catalog")
    @Operation(summary = "Get static library room and text-layout catalog")
    public ApiResponse<LibrarySeatRoomCatalogResponse> getSeatCatalog(
            @RequestParam(required = false)
            @Parameter(description = "Optional floor filter: B1, 2F, 5F, 6F, or numeric 2/5/6.")
            String floorCode,
            @RequestParam(required = false)
            @Parameter(description = "Optional room code filter, such as open-reading-2f.")
            String roomCode,
            @RequestParam(required = false, defaultValue = "false")
            @Parameter(description = "Include compact text-layout lines.")
            Boolean includeLayout
    ) {
        return ApiResponse.success(roomCatalogService.catalog(floorCode, roomCode, includeLayout));
    }
}
