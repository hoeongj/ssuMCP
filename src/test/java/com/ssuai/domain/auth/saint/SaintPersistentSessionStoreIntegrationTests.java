package com.ssuai.domain.auth.saint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.domain.auth.mcp.McpProviderHealth;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SaintPersistentSessionStoreIntegrationTests {

    @Autowired private SaintSessionRepository repository;
    @Autowired private SaintSessionStore persistentStore;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private SaintSessionStore callbackPod;
    private SaintSessionStore toolPod;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        transactions = new TransactionTemplate(transactionManager);
        SaintSessionProperties properties = properties();
        callbackPod = new SaintSessionStore(properties, repository);
        toolPod = new SaintSessionStore(properties, repository);
    }

    @Test
    void callbackCredentialHealthAndLogoutAreVisibleAcrossReplicaInstances() {
        transactions.executeWithoutResult(ignored -> callbackPod.putForSession(
                "credential-a", "upstream-user", new PortalCookies("rusaint-state-json")));

        String stateOnToolPod = transactions.execute(ignored -> toolPod.withSession(
                "credential-a", session -> {
                    assertThat(session.cookies().rawCookieHeader()).isEqualTo("rusaint-state-json");
                    session.cookies().refreshSessionJson("rusaint-state-v2");
                    return session.cookies().rawCookieHeader();
                }));
        assertThat(stateOnToolPod).isEqualTo("rusaint-state-v2");
        assertThat(transactions.execute(ignored -> callbackPod.cookies("credential-a"))
                .orElseThrow().sessionJson()).isEqualTo("rusaint-state-v2");
        assertThat(transactions.execute(ignored -> callbackPod.health("credential-a"))
                .orElseThrow().health()).isEqualTo(McpProviderHealth.VALID);

        transactions.executeWithoutResult(ignored -> callbackPod.invalidate("credential-a"));

        java.util.Optional<SaintSessionStore.SaintProviderSession> afterLogout =
                transactions.execute(ignored -> toolPod.session("credential-a"));
        assertThat(afterLogout).isEmpty();
    }

    @Test
    void webSessionCredentialCopyOwnsItsPersistentTransaction() {
        persistentStore.putForSession(
                "web-owner", "upstream-user", new PortalCookies("rusaint-state-json"));
        persistentStore.withSession("web-owner", session -> null);

        assertThat(persistentStore.copyForSession("web-owner", "mcp-owner")).isTrue();

        SaintSessionStore.SaintProviderSession copied =
                persistentStore.session("mcp-owner").orElseThrow();
        assertThat(copied.studentId()).isEqualTo("upstream-user");
        assertThat(copied.cookies().sessionJson()).isEqualTo("rusaint-state-json");

        SaintSessionEntity source = repository.findById("web-owner").orElseThrow();
        SaintSessionEntity target = repository.findById("mcp-owner").orElseThrow();
        assertThat(target.getCapturedAt()).isEqualTo(source.getCapturedAt());
        assertThat(target.getExpiresAt()).isEqualTo(source.getExpiresAt());
        assertThat(target.getHealth()).isEqualTo(source.getHealth());
        assertThat(target.getHealth()).isEqualTo(McpProviderHealth.VALID.name());
    }

    @Test
    void expiredProviderHealthIsNotCopiedIntoNewWebSession() {
        persistentStore.putForSession(
                "web-owner", "upstream-user", new PortalCookies("rusaint-state-json"));
        SaintSessionEntity source = repository.findById("web-owner").orElseThrow();
        source.updateCredential(
                source.getCookieIvB64(),
                source.getCookieCipherB64(),
                source.getCapturedAt(),
                source.getExpiresAt(),
                source.getCredentialVersion(),
                McpProviderHealth.EXPIRED.name(),
                source.getLastValidatedAt(),
                source.getLastSuccessfulCallAt(),
                source.getLastFailureAt(),
                "UPSTREAM_SESSION_EXPIRED");
        repository.save(source);

        assertThat(persistentStore.copyForSession("web-owner", "mcp-owner")).isFalse();
        assertThat(repository.findById("mcp-owner")).isEmpty();
    }

    private static SaintSessionProperties properties() {
        SaintSessionProperties properties = new SaintSessionProperties();
        properties.setTtl(Duration.ofMinutes(30));
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }
}
