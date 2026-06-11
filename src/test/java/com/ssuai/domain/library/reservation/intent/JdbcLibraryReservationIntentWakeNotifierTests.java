package com.ssuai.domain.library.reservation.intent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcLibraryReservationIntentWakeNotifierTests {

    @Test
    void h2DatasourceDoesNotSendPostgresNotify() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LibraryReservationIntentProperties properties = new LibraryReservationIntentProperties();
        JdbcLibraryReservationIntentWakeNotifier notifier =
                new JdbcLibraryReservationIntentWakeNotifier(
                        jdbcTemplate,
                        properties,
                        "jdbc:h2:mem:ssuai;MODE=PostgreSQL");

        notifier.notifyIntentReady(42L);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void postgresDatasourceSendsIntentWakeNotification() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LibraryReservationIntentProperties properties = new LibraryReservationIntentProperties();
        JdbcLibraryReservationIntentWakeNotifier notifier =
                new JdbcLibraryReservationIntentWakeNotifier(
                        jdbcTemplate,
                        properties,
                        "jdbc:postgresql://db/ssuai");

        notifier.notifyIntentReady(42L);

        ArgumentCaptor<ConnectionCallback<Void>> callback =
                ArgumentCaptor.forClass(ConnectionCallback.class);
        verify(jdbcTemplate).execute(callback.capture());

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        org.mockito.Mockito.when(connection.prepareStatement("select pg_notify(?, ?)"))
                .thenReturn(statement);

        callback.getValue().doInConnection(connection);

        verify(statement).setString(1, JdbcLibraryReservationIntentWakeNotifier.CHANNEL);
        verify(statement).setString(2, "42");
        verify(statement).execute();
        verify(statement).close();
    }
}
