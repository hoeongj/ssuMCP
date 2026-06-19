package com.ssuai.domain.auth.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class McpPrivateToolResponseTests {

    @Test
    void authRequired_populatesUserAndDeveloperMessages_andKeepsMessageAlias() {
        String loginUrl = "https://login.example/saint";
        McpPrivateToolResponse<Object> r =
                McpPrivateToolResponse.authRequired("sid-1", "SAINT", loginUrl, Instant.now());

        // userMessage: short, Korean, actionable (carries the loginUrl)
        assertThat(r.userMessage()).isNotBlank();
        assertThat(r.userMessage()).contains("로그인");
        assertThat(r.userMessage()).contains(loginUrl);
        assertThat(r.userMessage().length()).isLessThan(r.developerMessage().length());

        // developerMessage: verbose agent-facing procedure
        assertThat(r.developerMessage()).contains("AUTHENTICATION REQUIRED");
        assertThat(r.developerMessage()).contains("retry this exact tool call");

        // message stays a byte-identical backward-compatible alias of developerMessage
        assertThat(r.message()).isEqualTo(r.developerMessage());
    }

    @Test
    void invalidSession_populatesBothMessages() {
        McpPrivateToolResponse<Object> r = McpPrivateToolResponse.invalidSession("sid-2", "LMS");

        assertThat(r.userMessage()).contains("다시 로그인");
        assertThat(r.developerMessage()).contains("SESSION NOT FOUND");
        assertThat(r.message()).isEqualTo(r.developerMessage());
    }

    @Test
    void ok_hasNoMessages() {
        McpPrivateToolResponse<String> r = McpPrivateToolResponse.ok("sid-3", "payload");

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.data()).isEqualTo("payload");
        assertThat(r.message()).isNull();
        assertThat(r.userMessage()).isNull();
        assertThat(r.developerMessage()).isNull();
    }
}
