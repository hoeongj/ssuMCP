package com.ssuai.domain.chat.controller;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.chat.dto.ChatResponse;
import com.ssuai.domain.chat.service.ChatService;
import com.ssuai.global.exception.ChatUnavailableException;

@ActiveProfiles("test")
@WebMvcTest(ChatController.class)
class ChatControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Autowired
    ChatControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void replyWithValidBodyReturnsSuccessEnvelopeAndGeneratedConversationId() throws Exception {
        when(chatService.reply(anyString(), eq("오늘 학식 뭐야?"), org.mockito.ArgumentMatchers.isNull()))
                .thenAnswer(invocation -> new ChatResponse(invocation.getArgument(0), "오늘 학식은 mock 메뉴예요."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "오늘 학식 뭐야?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(matchesPattern("^c-[0-9a-f]{8}$")))
                .andExpect(jsonPath("$.data.reply").value("오늘 학식은 mock 메뉴예요."))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }

    @Test
    void replyPreservesProvidedConversationId() throws Exception {
        when(chatService.reply(eq("c-existing-1"), eq("카페 어디 있어?"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new ChatResponse("c-existing-1", "시설 검색 결과예요."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conversationId": "c-existing-1",
                                  "message": "카페 어디 있어?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value("c-existing-1"))
                .andExpect(jsonPath("$.data.reply").value("시설 검색 결과예요."))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void blankMessageReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingMessageReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void oversizedMessageReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s"
                                }
                                """.formatted("a".repeat(1001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidConversationIdReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conversationId": "bad_id",
                                  "message": "오늘 학식 뭐야?"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void chatUnavailableReturnsServiceUnavailableEnvelope() throws Exception {
        when(chatService.reply(anyString(), eq("오늘 학식 뭐야?"), org.mockito.ArgumentMatchers.isNull())).thenThrow(new ChatUnavailableException());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "오늘 학식 뭐야?"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CHAT_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }
}
