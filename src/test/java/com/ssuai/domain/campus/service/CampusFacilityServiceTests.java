package com.ssuai.domain.campus.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.campus.dto.CampusFacilityCategory;
import com.ssuai.domain.campus.dto.CampusFacilityResponse;

class CampusFacilityServiceTests {

    private final CampusFacilityService campusFacilityService = new CampusFacilityService();

    @Test
    void getFacilitiesReturnsStaticCampusFacilities() {
        var response = campusFacilityService.getFacilities();

        assertThat(response.facilities()).hasSize(20);

        CampusFacilityResponse residenceHallCafeteria = findById("residence-hall-cafeteria");
        assertThat(residenceHallCafeteria.name()).isEqualTo("레지던스홀 기숙사 식당");
        assertThat(residenceHallCafeteria.category()).isEqualTo(CampusFacilityCategory.CAFETERIA);
        assertThat(residenceHallCafeteria.location()).isEqualTo("레지던스홀 지하 1층");
        assertThat(residenceHallCafeteria.weekdayHours())
                .containsExactly("조식 미운영", "중식: 11:00~13:50", "석식: 17:00~18:30");
        assertThat(residenceHallCafeteria.weekendHours())
                .containsExactly("조식 미운영", "중식: 11:20~13:30", "석식: 17:00~18:20");
        assertThat(residenceHallCafeteria.notes())
                .contains(
                        "실시간 메뉴: GET /api/dorm/meals/this-week",
                        "식단 페이지: https://ssudorm.ssu.ac.kr:444/SShostel/mall_main.php?viewform=B0001_foodboard_list&board_no=1");

        CampusFacilityResponse studentCafeteria = findById("student-cafeteria");
        assertThat(studentCafeteria.name()).isEqualTo("학생식당");
        assertThat(studentCafeteria.category()).isEqualTo(CampusFacilityCategory.CAFETERIA);
        assertThat(studentCafeteria.categoryLabel()).isEqualTo("식당");
        assertThat(studentCafeteria.location()).isEqualTo("학생회관 3층");
        assertThat(studentCafeteria.phone()).isEqualTo("820-0882");
        assertThat(studentCafeteria.extension()).isEqualTo("0882");
        assertThat(studentCafeteria.weekdayHours())
                .containsExactly("08:00~09:00 (천원의아침밥)", "11:20~14:00 (식사 제공)", "14:00~17:00 (공간 개방)");
        assertThat(studentCafeteria.weekendHours()).containsExactly("휴무 (숭실도담 이용 바람)");

        CampusFacilityResponse iprint = findById("iprint");
        assertThat(iprint.fax()).isEqualTo("02-3280-9763");
        assertThat(iprint.aliases()).contains("복사", "출력", "프린트");
    }

    @Test
    void searchFacilitiesMatchesNameLocationCategoryAndAliases() {
        assertThat(campusFacilityService.searchFacilities("편의점").facilities())
                .hasSize(6)
                .extracting(CampusFacilityResponse::category)
                .containsOnly(CampusFacilityCategory.CONVENIENCE_STORE);

        assertThat(campusFacilityService.searchFacilities("스넥").facilities())
                .extracting(CampusFacilityResponse::name)
                .containsExactly("스낵코너");

        assertThat(campusFacilityService.searchFacilities("도서관").facilities())
                .extracting(CampusFacilityResponse::name)
                .containsExactly("도서관 커피점 (숭실마루)");

        assertThat(campusFacilityService.searchFacilities("복사").facilities())
                .extracting(CampusFacilityResponse::name)
                .containsExactly("아이프린트 (복사, 출력)");

        assertThat(campusFacilityService.searchFacilities("기숙사 식단").facilities())
                .extracting(CampusFacilityResponse::name)
                .containsExactly("레지던스홀 기숙사 식당");
    }

    @Test
    void searchFacilitiesReturnsAllFacilitiesForBlankQuery() {
        assertThat(campusFacilityService.searchFacilities("   ").facilities())
                .hasSize(campusFacilityService.getFacilities().facilities().size());
    }

    private CampusFacilityResponse findById(String id) {
        return campusFacilityService.getFacilities()
                .facilities()
                .stream()
                .filter(facility -> facility.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
