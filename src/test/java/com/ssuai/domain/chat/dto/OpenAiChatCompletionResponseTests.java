package com.ssuai.domain.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class OpenAiChatCompletionResponseTests {

    /**
     * OpenAI-compatible providers (e.g. Gemini) omit the per-choice "index" for the
     * first choice (index 0), exactly as Gemini omits the first embedding item's index.
     * A primitive {@code int index} made Jackson 3 throw MismatchedInputException and
     * discard the whole chat response. The record must parse without it
     * (TROUBLESHOOTING 사건 22).
     */
    @Test
    void parsesChatResponseWhenFirstChoiceOmitsIndex() {
        String json = "{\"id\":\"x\",\"model\":\"gemini\",\"choices\":["
                + "{\"message\":{\"role\":\"assistant\",\"content\":\"안녕하세요\"},\"finish_reason\":\"stop\"}"
                + "]}";

        OpenAiChatCompletionResponse response =
                JsonMapper.builder().build().readValue(json, OpenAiChatCompletionResponse.class);

        assertThat(response.choices()).hasSize(1);
        assertThat(response.firstMessage()).isNotNull();
        assertThat(response.firstMessage().content()).isEqualTo("안녕하세요");
        assertThat(response.firstMessage().role()).isEqualTo("assistant");
    }
}
