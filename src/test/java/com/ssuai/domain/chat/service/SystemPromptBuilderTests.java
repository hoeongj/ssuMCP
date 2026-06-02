package com.ssuai.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemPromptBuilderTests {

    private SystemPromptBuilder promptBuilder;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        // 2026-03-05T20:00Z = 2026-03-06T05:00 KST (Friday)
        fixedClock = Clock.fixed(Instant.parse("2026-03-05T20:00:00Z"), ZoneOffset.UTC);
        promptBuilder = new SystemPromptBuilder(fixedClock);
    }

    @Test
    void getBaseSystemPromptReturnsStaticPrompt() {
        String basePrompt = promptBuilder.getBaseSystemPrompt();
        assertThat(basePrompt)
                .isNotBlank()
                .contains("ssuAI 챗봇")
                .contains("친근한 선배처럼 편하게")
                .contains("tavily_search")
                .contains("학교 관련 질문은 내부 도구가 없어도 웹 검색으로 시도해");
    }

    @Test
    void buildTodayContextMessageHonorsKstZoneAcrossUtcDayBoundary() {
        String todayMessage = promptBuilder.buildTodayContextMessage();

        assertThat(todayMessage)
                .contains("2026-03-06")
                .contains("(금)")
                .contains("Asia/Seoul")
                .contains("yyyy-MM-dd");
    }

    @Test
    void buildAuthContextMessageReturnsAuthenticatedMessageForValidStudentId() {
        String authMessage = promptBuilder.buildAuthContextMessage("20241234");

        assertThat(authMessage)
                .contains("인증됨")
                .contains("get_my_schedule")
                .contains("start_auth 같은 도구는 이 챗봇에 없어");
    }

    @Test
    void buildAuthContextMessageReturnsUnauthenticatedMessageForBlankOrNullStudentId() {
        String authMessageNull = promptBuilder.buildAuthContextMessage(null);
        String authMessageBlank = promptBuilder.buildAuthContextMessage("   ");

        assertThat(authMessageNull)
                .contains("비인증")
                .contains("성적·시간표·채플·졸업요건·장학금 등 개인 정보는 접근 불가")
                .contains("대시보드에서 SmartID로 로그인하면 볼 수 있어요");

        assertThat(authMessageBlank)
                .contains("비인증")
                .contains("성적·시간표·채플·졸업요건·장학금 등 개인 정보는 접근 불가");
    }
}
