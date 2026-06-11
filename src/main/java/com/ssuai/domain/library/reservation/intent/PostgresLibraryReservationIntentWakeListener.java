package com.ssuai.domain.library.reservation.intent;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class PostgresLibraryReservationIntentWakeListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PostgresLibraryReservationIntentWakeListener.class);

    private final DataSource dataSource;
    private final LibraryReservationIntentProperties properties;
    private final LibraryReservationWorker worker;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    PostgresLibraryReservationIntentWakeListener(
            DataSource dataSource,
            LibraryReservationIntentProperties properties,
            LibraryReservationWorker worker,
            Environment environment) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.worker = worker;
        this.enabled = properties.isNotifyWakeEnabled()
                && JdbcLibraryReservationIntentWakeNotifier.isPostgresUrl(
                        environment.getProperty("spring.datasource.url", ""));
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::listenLoop, "library-reservation-intent-notify-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running.set(false);
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void listenLoop() {
        while (running.get()) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                listen(connection);
                PGConnection pgConnection = connection.unwrap(PGConnection.class);
                worker.wake();
                receiveNotifications(pgConnection);
            } catch (SQLException | RuntimeException exception) {
                if (running.get()) {
                    log.warn("library reservation intent notify listener disconnected", exception);
                    sleep(properties.getNotifyReconnectDelay());
                }
            }
        }
    }

    private void listen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + JdbcLibraryReservationIntentWakeNotifier.CHANNEL);
        }
    }

    private void receiveNotifications(PGConnection pgConnection) throws SQLException {
        int timeoutMillis = Math.toIntExact(properties.getNotifyListenTimeout().toMillis());
        while (running.get()) {
            PGNotification[] notifications = pgConnection.getNotifications(timeoutMillis);
            if (notifications != null && notifications.length > 0) {
                worker.wake();
            }
        }
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
