package com.ssuai.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MockChatServiceTests {

    private final MockChatService chatService = new MockChatService();

    @Test
    void mealKeywordReturnsMockMealReply() {
        assertThat(chatService.reply("c-test", "오늘 학식 뭐야?").reply())
                .isEqualTo("[mock] 오늘 학식은 mock 메뉴예요.");
    }

    @Test
    void dormKeywordReturnsMockDormReply() {
        assertThat(chatService.reply("c-test", "이번 주 기숙사 식단 알려줘").reply())
                .isEqualTo("[mock] 이번 주 기숙사 식단은 mock 데이터예요.");
    }

    @Test
    void facilityKeywordReturnsMockFacilityReply() {
        assertThat(chatService.reply("c-test", "캠퍼스에 카페 어디 있어?").reply())
                .isEqualTo("[mock] 캠퍼스 시설 검색 결과는 mock 데이터예요.");
    }

    @Test
    void unrelatedQueryReturnsScopeGuidance() {
        assertThat(chatService.reply("c-test", "안녕").reply())
                .isEqualTo("[mock] 지금은 학식, 기숙사 식단, 캠퍼스 시설 질문만 도와줄 수 있어요.");
    }
}
