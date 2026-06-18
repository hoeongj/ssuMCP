package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthSessionStore;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.McpSessionEntity;
import com.ssuai.domain.auth.mcp.McpSessionRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class McpAuthHelperIntegrationTests {

    private static final Instant T0 = Instant.parse("2026-06-18T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2099-01-01T00:00:00Z");
    private static final String TRANSPORT_ID = "transport-abc";
    private static final McpAuthSessionId OLDER_SESSION = new McpAuthSessionId("old-session");
    private static final McpAuthSessionId NEWER_SESSION = new McpAuthSessionId("new-session");

    @Autowired
    private McpSessionRepository repository;

    @Autowired
    private McpAuthSessionStore store;

    @Autowired
    private McpAuthService mcpAuthService;

    private McpAuthHelper helper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        repository.deleteAll();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Mcp-Session-Id")).thenReturn(TRANSPORT_ID);
        helper = new McpAuthHelper(mcpAuthService, mock(McpAuthUrlFactory.class), request);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveSessionAndPrincipalKeyUseNewestTransportSessionWhenDuplicatesExist() {
        McpSessionEntity older = new McpSessionEntity(
                OLDER_SESSION.value(),
                T0,
                EXPIRES,
                "{}"
        );
        older.setTransportSessionId(TRANSPORT_ID);
        McpSessionEntity newer = new McpSessionEntity(
                NEWER_SESSION.value(),
                T0.plusSeconds(1),
                EXPIRES,
                "{}"
        );
        newer.setTransportSessionId(TRANSPORT_ID);
        repository.save(older);
        repository.save(newer);
        store.linkProvider(OLDER_SESSION, McpProviderType.SAINT, "older-student");
        store.linkProvider(NEWER_SESSION, McpProviderType.SAINT, "newer-student");

        Optional<McpAuthSession> resolved = helper.resolveSession(null);
        Optional<String> principalKey = helper.principalKey(null, McpProviderType.SAINT);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(NEWER_SESSION);
        assertThat(principalKey).contains("newer-student");
    }
}
