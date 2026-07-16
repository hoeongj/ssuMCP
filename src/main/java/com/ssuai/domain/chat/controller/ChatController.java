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
import com.ssuai.domain.library.auth.LibrarySessionKeyResolver;
import com.ssuai.domain.library.mcp.LibraryToolContext;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "Chat", description = "Text-only chatbot API")
public class ChatController {

    private final ChatService chatService;
    private final LibrarySessionKeyResolver librarySessionKeyResolver;

    public ChatController(ChatService chatService, LibrarySessionKeyResolver librarySessionKeyResolver) {
        this.chatService = chatService;
        this.librarySessionKeyResolver = librarySessionKeyResolver;
    }

    @PostMapping
    @Operation(summary = "Send a message to the MVP chatbot")
    public ApiResponse<ChatResponse> reply(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {
        String conversationId = resolveConversationId(request.conversationId());
        String studentId = (String) httpRequest.getAttribute(AuthAttributes.STUDENT_ID);
        // The private library loan tool looks up the Pyxis token by LibrarySessionStore key,
        // which is now the persistent library-session cookie, not this servlet session id
        // (ADR 0096) — resolve it separately so chat-invoked
        // library tools keep working across redeploys/pod switches the same as the REST paths.
        // Resolved BEFORE the owner-scoping getSession() below: otherwise the resolver's legacy
        // servlet-session fallback would pick up the session id that getSession() just minted —
        // a key that was never bound in the store.
        String librarySessionKey = librarySessionKeyResolver.resolve(httpRequest).orElse(null);
        String sessionKey = httpRequest.getSession().getId();
        // Isolate conversation memory per owner: the authenticated student
        // when present, else the server session so anonymous callers are
        // still scoped to their own session and can never read another
        // owner's history by passing/guessing their conversationId.
        String owner = studentId != null && !studentId.isBlank() ? studentId : sessionKey;
        try (LibraryToolContext.Scope ignored = LibraryToolContext.withSessionKey(librarySessionKey)) {
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
