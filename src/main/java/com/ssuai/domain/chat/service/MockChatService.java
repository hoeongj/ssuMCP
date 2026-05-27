package com.ssuai.domain.chat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.ssuai.domain.chat.dto.ChatResponse;

@Service
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "mock", matchIfMissing = true)
public class MockChatService implements ChatService {

    @Override
    public ChatResponse reply(String conversationId, String message, String studentId) {
        String normalized = message == null ? "" : message.toLowerCase();
        String reply;

        if (containsAny(normalized, "학식", "식당", "메뉴", "밥")) {
            reply = "[mock] 오늘 학식은 mock 메뉴예요.";
        } else if (containsAny(normalized, "기숙사", "식단", "레지던스")) {
            reply = "[mock] 이번 주 기숙사 식단은 mock 데이터예요.";
        } else if (containsAny(normalized, "카페", "시설", "복사", "출력", "프린트", "매점")) {
            reply = "[mock] 캠퍼스 시설 검색 결과는 mock 데이터예요.";
        } else {
            reply = "[mock] 지금은 학식, 기숙사 식단, 캠퍼스 시설 질문만 도와줄 수 있어요.";
        }

        return new ChatResponse(conversationId, reply);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
