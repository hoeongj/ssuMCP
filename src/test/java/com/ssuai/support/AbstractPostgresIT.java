package com.ssuai.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests that need a prod-equivalent Postgres instead of H2.
 *
 * <p>A single Postgres 16 container is shared across subclasses (static {@code @Container}).
 * {@code @ServiceConnection} rewires Spring's {@code DataSource} at it, so Flyway applies the
 * {@code postgresql} vendor migrations and Hibernate validates entities against real PG types
 * ({@code TIMESTAMP(6) WITH TIME ZONE}, {@code TEXT}, composite keys) — dialect/type drift that
 * the in-memory H2 datasource silently accepts.
 *
 * <p>{@code disabledWithoutDocker = true} makes the whole class self-skip when no Docker daemon
 * is reachable, so the offline {@code ./gradlew test} stays green; CI (Docker present) runs it.
 * The annotation is {@code @Inherited}, so subclasses pick up the container + Spring config.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
public abstract class AbstractPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
