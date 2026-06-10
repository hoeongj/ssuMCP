package com.ssuai.domain.mcp.tool;

import com.ssuai.domain.library.recommendation.LibrarySeatCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationService;

/**
 * Pyxis seat ids (externalSeatId, e.g. 3196) are not the seat numbers users see
 * on the floor (label, e.g. 91). User-facing messages must always resolve the
 * external id through the catalog and never present it as a "N번 좌석" number.
 */
final class SeatDisplay {

    private SeatDisplay() {
    }

    static String describe(LibrarySeatCatalogService catalogService, long externalSeatId) {
        return catalogService.findByExternalSeatId(Long.toString(externalSeatId))
                .map(entry -> String.format("%s %s번 좌석(좌석ID %d)", entry.roomName(), entry.label(), externalSeatId))
                .orElse(String.format("좌석ID %d(카탈로그에 없는 좌석이라 좌석 번호 미확인)", externalSeatId));
    }

    static String graduateOnlyWarning(LibrarySeatCatalogService catalogService, long externalSeatId) {
        boolean graduateOnly = catalogService.findByExternalSeatId(Long.toString(externalSeatId))
                .map(LibrarySeatCatalogEntry::audience)
                .filter(LibrarySeatRecommendationService.GRADUATE_ONLY_AUDIENCE::equals)
                .isPresent();
        if (!graduateOnly) {
            return "";
        }
        return " 주의: 이 좌석은 대학원 전용 열람실입니다. 학부생은 이용 자격이 없을 수 있으니 확인 후 진행하세요.";
    }
}
