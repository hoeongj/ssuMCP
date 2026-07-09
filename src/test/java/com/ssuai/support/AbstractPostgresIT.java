package com.ssuai.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests that need a prod-equivalent Postgres instead of H2.
 *
 * <p>A single Postgres 16 container is started once for the whole JVM and shared by every
 * subclass (singleton-container pattern) — it is deliberately NOT annotated
 * {@code @Container}. Letting the JUnit {@code TestcontainersExtension} own a {@code static}
 * container field stops it after each test class and restarts it for the next one, which
 * hands out a brand-new mapped port every time. Spring's {@code @SpringBootTest} context cache
 * key is identical across all subclasses (none add class-level annotations), so whether a given
 * subclass gets a fresh context or reuses a previous subclass's cached one is pure luck of
 * execution order / cache eviction. A reused context keeps the {@code HikariDataSource} bean
 * created for the OLD container port; once that old container is stopped, every connection in
 * the pool dies and new ones refuse (port no longer listening) even though a live container is
 * running right next to it on a different port. This is exactly what broke PR #179's
 * {@code BackgroundProcessorClaimConcurrencyIT}: it landed right after
 * {@code LibraryReservationIntentConcurrencyIT} with no evicting tests in between, so it
 * inherited that class's cached context/pool pointed at an already-stopped container, while
 * {@code AcademicEmbeddingPostgresIT} (which ran much earlier, with many unrelated contexts
 * created in between) had already been evicted from the cache and got its own fresh
 * container-backed pool.
 *
 * <p>Starting the container once in a static initializer — and never stopping it — means the
 * mapped port never changes for the life of the JVM, so a stale cached context can never point
 * at a dead port. Testcontainers' Ryuk reaper still cleans the container up when the JVM exits.
 * {@code @ServiceConnection} does not require {@code @Container}; it works by reflectively
 * finding the annotated field regardless of who manages the container's lifecycle.
 *
 * <p>{@code disabledWithoutDocker = true} still makes the whole class self-skip when no Docker
 * daemon is reachable, so the offline {@code ./gradlew test} stays green; CI (Docker present)
 * runs it. The annotation is {@code @Inherited}, so subclasses pick up the Spring config.
 * The static initializer independently guards the actual {@code start()} call with the same
 * Docker-availability check so constructing this class off-CI never attempts to talk to Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
public abstract class AbstractPostgresIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }
}
