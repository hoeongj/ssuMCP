package com.ssuai.domain.saint.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Loads the native rusaint FFI at boot so the first u-SAINT login is never the
 * request that pays the cold native-load cost (ADR 0076).
 *
 * <p>The warmup runs on a daemon thread: it must not delay the context becoming
 * ready (k8s readiness flips as soon as the health endpoint is up), and real
 * logins arrive minutes after a pod starts, so asynchronous warmth is ready long
 * before anyone needs it. Any failure is swallowed inside {@link RusaintClient#warmUp()}.
 *
 * <p>Disabled under the {@code test} profile so unit/integration tests don't
 * eagerly dlopen the native library; the runner's own behavior is covered by
 * {@code RusaintFfiWarmupTests} with a mock client.
 */
@Component
@Profile("!test")
class RusaintFfiWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RusaintFfiWarmup.class);

    private final RusaintClient rusaintClient;

    RusaintFfiWarmup(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread warmupThread = new Thread(this::warmUpAndLog, "rusaint-ffi-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    private void warmUpAndLog() {
        long startNanos = System.nanoTime();
        rusaintClient.warmUp();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("rusaint FFI warmup finished in {}ms", elapsedMs);
    }
}
