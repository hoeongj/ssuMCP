package com.ssuai.domain.auth.lms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
class LmsPersistentSessionStoreIntegrationTests {

    @Autowired private LmsSessionRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LmsSessionStore persistentStore;

    private TransactionTemplate transactions;
    private LmsSessionStore callbackPod;
    private LmsSessionStore toolPod;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        transactions = new TransactionTemplate(transactionManager);
        LmsSessionProperties properties = properties();
        callbackPod = new LmsSessionStore(properties, repository);
        toolPod = new LmsSessionStore(properties, repository);
    }

    @Test
    void callbackCredentialAndLogoutAreVisibleAcrossReplicaInstances() {
        transactions.executeWithoutResult(ignored -> callbackPod.putForSession(
                "credential-a", "upstream-user", new LmsCookies("xn_api_token=one; route=a")));

        LmsSessionStore.LmsProviderSession onToolPod = transactions.execute(ignored ->
                toolPod.session("credential-a").orElseThrow());
        assertThat(onToolPod.studentId()).isEqualTo("upstream-user");
        assertThat(onToolPod.cookies().rawCookieHeader()).contains("xn_api_token=one", "route=a");

        transactions.executeWithoutResult(ignored -> callbackPod.invalidate("credential-a"));

        java.util.Optional<LmsSessionStore.LmsProviderSession> afterLogout =
                transactions.execute(ignored -> toolPod.session("credential-a"));
        assertThat(afterLogout).isEmpty();
    }

    @Test
    void nonConflictingOutOfOrderCookieUpdatesSurviveAcrossReplicaInstances()
            throws Exception {
        transactions.executeWithoutResult(ignored -> callbackPod.putForSession(
                "credential-a", "upstream-user", new LmsCookies("base=one")));
        LmsCookies snapshot = transactions.execute(ignored ->
                callbackPod.cookies("credential-a").orElseThrow());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> route = executor.submit(() -> {
                await(start);
                transactions.executeWithoutResult(ignored -> callbackPod.mergeSetCookie(
                        snapshot, List.of("route=rotated; Path=/")));
            });
            Future<?> csrf = executor.submit(() -> {
                await(start);
                transactions.executeWithoutResult(ignored -> toolPod.mergeSetCookie(
                        snapshot, List.of("csrf=rotated; Path=/")));
            });
            start.countDown();
            route.get();
            csrf.get();
        } finally {
            executor.shutdownNow();
        }

        String canonical = transactions.execute(ignored ->
                toolPod.cookies("credential-a").orElseThrow().rawCookieHeader());
        assertThat(canonical).contains("base=one", "route=rotated", "csrf=rotated");
    }

    @Test
    void responseCookieCommitSurvivesCallerTransactionRollback() {
        persistentStore.putForSession("credential-a", "upstream-user", new LmsCookies("route=old"));
        LmsCookies snapshot = persistentStore.cookies("credential-a").orElseThrow();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            persistentStore.mergeSetCookie(snapshot, List.of("route=rotated; Path=/"));
            status.setRollbackOnly();
            throw new IllegalStateException("downstream response parsing failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(persistentStore.cookies("credential-a").orElseThrow().rawCookieHeader())
                .contains("route=rotated");
    }

    @Test
    void webSessionCredentialCopyOwnsItsPersistentTransaction() {
        persistentStore.putForSession(
                "web-owner", "upstream-user", new LmsCookies("xn_api_token=one; route=a"));
        persistentStore.withSession("web-owner", session -> null);

        assertThat(persistentStore.copyForSession("web-owner", "mcp-owner")).isTrue();

        LmsSessionStore.LmsProviderSession copied =
                persistentStore.session("mcp-owner").orElseThrow();
        assertThat(copied.studentId()).isEqualTo("upstream-user");
        assertThat(copied.cookies().rawCookieHeader()).contains("xn_api_token=one", "route=a");

        LmsSessionEntity source = repository.findById("web-owner").orElseThrow();
        LmsSessionEntity target = repository.findById("mcp-owner").orElseThrow();
        assertThat(target.getCapturedAt()).isEqualTo(source.getCapturedAt());
        assertThat(target.getExpiresAt()).isEqualTo(source.getExpiresAt());
        assertThat(target.getHealth()).isEqualTo(source.getHealth());
        assertThat(target.getHealth()).isEqualTo(McpProviderHealth.VALID.name());
    }

    @Test
    void expiredProviderHealthIsNotCopiedIntoNewWebSession() {
        persistentStore.putForSession(
                "web-owner", "upstream-user", new LmsCookies("xn_api_token=one; route=a"));
        LmsSessionEntity source = repository.findById("web-owner").orElseThrow();
        source.updateCredential(
                source.getCookieIvB64(),
                source.getCookieCipherB64(),
                source.getCapturedAt(),
                source.getExpiresAt(),
                source.getCredentialVersion(),
                source.getCookieVersions(),
                McpProviderHealth.EXPIRED.name(),
                source.getLastValidatedAt(),
                source.getLastSuccessfulCallAt(),
                source.getLastFailureAt(),
                "UPSTREAM_SESSION_EXPIRED");
        repository.save(source);

        assertThat(persistentStore.copyForSession("web-owner", "mcp-owner")).isFalse();
        assertThat(repository.findById("mcp-owner")).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static LmsSessionProperties properties() {
        LmsSessionProperties properties = new LmsSessionProperties();
        properties.setTtl(Duration.ofHours(2));
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }
}
