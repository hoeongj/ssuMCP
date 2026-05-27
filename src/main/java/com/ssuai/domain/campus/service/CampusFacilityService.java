package com.ssuai.domain.campus.service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ssuai.domain.campus.dto.CampusFacilityCategory;
import com.ssuai.domain.campus.dto.CampusFacilityListResponse;
import com.ssuai.domain.campus.dto.CampusFacilityResponse;

@Service
public class CampusFacilityService {

    // Static public campus facility data compiled from user-provided school store/restaurant information.
    private static final List<CampusFacilityResponse> FACILITIES = List.of(
            facility(
                    "residence-hall-cafeteria",
                    "레지던스홀 기숙사 식당",
                    CampusFacilityCategory.CAFETERIA,
                    "레지던스홀 지하 1층",
                    null,
                    null,
                    null,
                    List.of("조식 미운영", "중식: 11:00~13:50", "석식: 17:00~18:30"),
                    List.of("조식 미운영", "중식: 11:20~13:30", "석식: 17:00~18:20"),
                    List.of(
                            "실시간 메뉴: GET /api/dorm/meals/this-week",
                            "식단 페이지: https://ssudorm.ssu.ac.kr:444/SShostel/mall_main.php?viewform=B0001_foodboard_list&board_no=1",
                            "식자재 수급현황에 따라 메뉴가 변경될 수 있음"
                    ),
                    List.of("기숙사 식당", "기숙사식당", "기숙사 식단", "레지던스홀 식당", "생활관 식당")
            ),
            facility(
                    "student-cafeteria",
                    "학생식당",
                    CampusFacilityCategory.CAFETERIA,
                    "학생회관 3층",
                    "820-0882",
                    "0882",
                    null,
                    List.of("08:00~09:00 (천원의아침밥)", "11:20~14:00 (식사 제공)", "14:00~17:00 (공간 개방)"),
                    List.of("휴무 (숭실도담 이용 바람)"),
                    List.of(),
                    List.of("학식", "학생회관 식당")
            ),
            facility(
                    "student-hall-coopsket",
                    "학생회관 쿱스켓 편의점",
                    CampusFacilityCategory.CONVENIENCE_STORE,
                    "학생회관 4층",
                    null,
                    null,
                    null,
                    List.of("유인 운영: 08:00~17:00", "무인 운영: 24시간"),
                    List.of("유인 운영: 09:00~14:00", "무인 운영: 24시간"),
                    List.of(),
                    List.of("학생회관 편의점", "쿱스켓")
            ),
            facility(
                    "bookstore-stationery",
                    "서점&문구점",
                    CampusFacilityCategory.BOOKSTORE_STATIONERY,
                    "학생회관 4층",
                    "820-0886",
                    null,
                    null,
                    List.of("09:00~18:00"),
                    List.of("휴무"),
                    List.of(),
                    List.of("서점", "문구점", "문방구")
            ),
            facility(
                    "jomansik-cafe",
                    "조만식기념관 커피점",
                    CampusFacilityCategory.CAFE,
                    "조만식기념관 1층",
                    null,
                    null,
                    null,
                    List.of("08:30~18:00"),
                    List.of("08:30~14:00"),
                    List.of(),
                    List.of("조만식 카페", "조만식기념관 카페")
            ),
            facility(
                    "dodam-cafeteria",
                    "숭실도담식당",
                    CampusFacilityCategory.CAFETERIA,
                    "신양관 2층",
                    "820-0890",
                    null,
                    null,
                    List.of("11:20~14:00 (점심)", "17:00~18:30 (저녁)"),
                    List.of("11:20~13:30 (점심)"),
                    List.of(),
                    List.of("도담식당", "도담", "신양관 식당")
            ),
            facility(
                    "shinyang-coopsket",
                    "신양관 쿱스켓 편의점",
                    CampusFacilityCategory.CONVENIENCE_STORE,
                    "신양관 1층",
                    null,
                    null,
                    null,
                    List.of("유인 운영: 08:00~17:00", "무인 운영: 24시간"),
                    List.of("유인 운영: 09:00~14:00", "무인 운영: 24시간"),
                    List.of(),
                    List.of("신양관 편의점", "쿱스켓")
            ),
            facility(
                    "soongdeok-cafe",
                    "숭덕경상관 커피점",
                    CampusFacilityCategory.CAFE,
                    "숭덕경상관 2층",
                    null,
                    null,
                    null,
                    List.of("08:30~18:00"),
                    List.of("08:30~13:30"),
                    List.of(),
                    List.of("숭덕경상관 카페", "숭덕 카페")
            ),
            facility(
                    "snack-corner",
                    "스낵코너",
                    CampusFacilityCategory.SNACK,
                    "학생회관 3층",
                    "820-0892",
                    null,
                    null,
                    List.of("11:00~15:30"),
                    List.of("휴무"),
                    List.of(),
                    List.of("스넥코너", "학생회관 스낵")
            ),
            facility(
                    "jomansik-coopsket",
                    "조만식기념관 쿱스켓 편의점",
                    CampusFacilityCategory.CONVENIENCE_STORE,
                    "조만식기념관 2층",
                    null,
                    null,
                    null,
                    List.of("유인 운영: 08:00~17:00", "무인 운영: 24시간"),
                    List.of("유인 운영: 09:00~12:00", "무인 운영: 24시간"),
                    List.of(),
                    List.of("조만식 편의점", "쿱스켓")
            ),
            facility(
                    "library-soongsil-maru",
                    "도서관 커피점 (숭실마루)",
                    CampusFacilityCategory.CAFE,
                    "도서관 6층",
                    null,
                    null,
                    null,
                    List.of("11:00~17:30"),
                    List.of("휴무"),
                    List.of(),
                    List.of("숭실마루", "도서관 카페")
            ),
            facility(
                    "cafe-ing",
                    "CAFE ING",
                    CampusFacilityCategory.CAFE,
                    "웨스트민스트홀 3층",
                    null,
                    null,
                    null,
                    List.of("08:30~20:00"),
                    List.of("08:30~18:00 (토요일)"),
                    List.of("일요일 운영 정보는 제공되지 않음"),
                    List.of("카페잉", "웨스트민스트홀 카페")
            ),
            facility(
                    "hyungnam-coopsket",
                    "형남공학관 쿱스켓 편의점",
                    CampusFacilityCategory.CONVENIENCE_STORE,
                    "형남공학관 2층",
                    null,
                    null,
                    null,
                    List.of("유인 운영: 08:00~16:00", "무인 운영: 24시간"),
                    List.of("유인 운영: 09:00~12:00", "무인 운영: 24시간"),
                    List.of(),
                    List.of("형남 편의점", "쿱스켓")
            ),
            facility(
                    "faculty-lounge",
                    "Faculty Lounge",
                    CampusFacilityCategory.CAFETERIA,
                    "전산관 B1",
                    null,
                    null,
                    null,
                    List.of("11:30~14:00 (식사 제공)", "14:00~17:00 (공간 개방)"),
                    List.of("휴무"),
                    List.of(),
                    List.of("교직원 식당", "전산관 식당", "팩컬티 라운지")
            ),
            facility(
                    "ahn-iktae-cafe",
                    "안익태기념관 커피점",
                    CampusFacilityCategory.CAFE,
                    "안익태기념관 1층",
                    null,
                    null,
                    null,
                    List.of("10:00~17:00"),
                    List.of("휴무"),
                    List.of(),
                    List.of("안익태 카페", "안익태기념관 카페")
            ),
            facility(
                    "soongdeok-coopsket",
                    "숭덕경상관 쿱스켓",
                    CampusFacilityCategory.CONVENIENCE_STORE,
                    "숭덕경상관 1층",
                    null,
                    null,
                    null,
                    List.of("유인 운영: 08:00~16:00", "무인 운영: 24시간"),
                    List.of("유인 운영: 09:00~12:00", "무인 운영: 24시간"),
                    List.of(),
                    List.of("숭덕경상관 편의점", "쿱스켓")
            ),
            facility(
                    "jeonsan-coopsket",
                    "전산관 쿱스켓",
                    CampusFacilityCategory.CONVENIENCE_STORE,
                    "전산관 1층",
                    null,
                    null,
                    null,
                    List.of("유인 운영: 08:00~16:00", "무인 운영: 24시간"),
                    List.of("유인 운영: 09:00~12:00", "무인 운영: 24시간"),
                    List.of(),
                    List.of("전산관 편의점", "쿱스켓")
            ),
            facility(
                    "tous-les-jours",
                    "뚜레쥬르",
                    CampusFacilityCategory.BAKERY,
                    "학생회관 2층",
                    "02-3280-9486",
                    null,
                    null,
                    List.of("07:00~21:00"),
                    List.of("07:00~21:00 (토요일)", "일요일 휴무"),
                    List.of(),
                    List.of("빵집", "학생회관 베이커리")
            ),
            facility(
                    "soongsil-gift-shop",
                    "숭실대학교 기념품샵",
                    CampusFacilityCategory.GIFT_SHOP,
                    "숭실대학교 문화관 105-1호",
                    "02-3280-0776",
                    null,
                    null,
                    List.of("10:00~17:30"),
                    List.of("휴무"),
                    List.of(),
                    List.of("기념품샵", "굿즈샵", "문화관 기념품샵")
            ),
            facility(
                    "iprint",
                    "아이프린트 (복사, 출력)",
                    CampusFacilityCategory.PRINT_SHOP,
                    "학생회관 4층 쿱스켓 안",
                    "02-3280-9762",
                    null,
                    "02-3280-9763",
                    List.of("09:00~18:00"),
                    List.of("휴무"),
                    List.of(),
                    List.of("아이프린트", "복사", "출력", "프린트")
            )
    );

    private static final List<SearchableFacility> SEARCH_INDEX = FACILITIES.stream()
            .map(facility -> new SearchableFacility(facility, normalizedSearchText(facility)))
            .toList();

    public CampusFacilityListResponse getFacilities() {
        return new CampusFacilityListResponse(FACILITIES);
    }

    public CampusFacilityListResponse searchFacilities(String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return getFacilities();
        }

        List<CampusFacilityResponse> filtered = SEARCH_INDEX.stream()
                .filter(indexed -> indexed.normalizedSearchText().contains(normalizedQuery))
                .map(SearchableFacility::facility)
                .toList();
        return new CampusFacilityListResponse(filtered);
    }

    private static String normalizedSearchText(CampusFacilityResponse facility) {
        return searchableValues(facility)
                .map(CampusFacilityService::normalize)
                .collect(Collectors.joining("\n"));
    }

    private static Stream<String> searchableValues(CampusFacilityResponse facility) {
        return Stream.concat(
                Stream.of(
                        facility.id(),
                        facility.name(),
                        facility.category().name(),
                        facility.categoryLabel(),
                        facility.location(),
                        facility.phone(),
                        facility.extension(),
                        facility.fax()
                ),
                Stream.of(
                        facility.weekdayHours(),
                        facility.weekendHours(),
                        facility.notes(),
                        facility.aliases()
                ).flatMap(List::stream)
        ).filter(value -> value != null && !value.isBlank());
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static CampusFacilityResponse facility(
            String id,
            String name,
            CampusFacilityCategory category,
            String location,
            String phone,
            String extension,
            String fax,
            List<String> weekdayHours,
            List<String> weekendHours,
            List<String> notes,
            List<String> aliases
    ) {
        return new CampusFacilityResponse(
                id,
                name,
                category,
                category.label(),
                location,
                phone,
                extension,
                fax,
                weekdayHours,
                weekendHours,
                notes,
                aliases
        );
    }

    private record SearchableFacility(CampusFacilityResponse facility, String normalizedSearchText) {
    }
}
