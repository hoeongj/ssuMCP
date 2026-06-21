package com.ssuai.domain.chat.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.chat.dto.ChatRequest;
import com.ssuai.domain.chat.dto.ChatResponse;
import com.ssuai.domain.chat.service.ChatService;
import com.ssuai.domain.library.mcp.LibraryToolContext;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "Chat", description = "Text-only chatbot API")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @Operation(summary = "Send a message to the MVP chatbot")
    public ApiResponse<ChatResponse> reply(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {
        String conversationId = resolveConversationId(request.conversationId());
        String studentId = (String) httpRequest.getAttribute(AuthAttributes.STUDENT_ID);
        String sessionKey = httpRequest.getSession().getId();
        // Isolate conversation memory per owner: the authenticated student
        // when present, else the server session so anonymous callers are
        // still scoped to their own session and can never read another
        // owner's history by passing/guessing their conversationId.
        String owner = studentId != null && !studentId.isBlank() ? studentId : sessionKey;
        try (LibraryToolContext.Scope ignored = LibraryToolContext.withSessionKey(sessionKey)) {
            ChatResponse response = chatService.reply(owner, conversationId, request.message(), studentId);
            return ApiResponse.success(response);
        }
    }

    private static String resolveConversationId(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        // Full 128-bit id (not a 32-bit prefix): now that history is
        // namespaced per owner the id is no longer a security boundary, but
        // a full UUID keeps server-issued ids collision-free.
        return UUID.randomUUID().toString();
    }
}
