package com.ssuai.domain.library.reservation.intent;

import java.sql.PreparedStatement;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcLibraryReservationIntentWakeNotifier implements LibraryReservationIntentWakeNotifier {

    static final String CHANNEL = "library_reservation_intent_wake";

    private static final Logger log = LoggerFactory.getLogger(JdbcLibraryReservationIntentWakeNotifier.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    @Autowired
    JdbcLibraryReservationIntentWakeNotifier(
            DataSource dataSource,
            LibraryReservationIntentProperties properties,
            Environment environment) {
        this(new JdbcTemplate(dataSource), properties, environment.getProperty("spring.datasource.url", ""));
    }

    JdbcLibraryReservationIntentWakeNotifier(
            JdbcTemplate jdbcTemplate,
            LibraryReservationIntentProperties properties,
            String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = properties.isNotifyWakeEnabled() && isPostgresUrl(datasourceUrl);
    }

    @Override
    public void notifyIntentReady(Long intentId) {
        if (!enabled || intentId == null) {
            return;
        }
        try {
            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                try (PreparedStatement statement = connection.prepareStatement("select pg_notify(?, ?)")) {
                    statement.setString(1, CHANNEL);
                    statement.setString(2, intentId.toString());
                    statement.execute();
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("library reservation intent notify failed: intentId={}", intentId, exception);
        }
    }

    static boolean isPostgresUrl(String datasourceUrl) {
        return datasourceUrl != null
                && datasourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:");
    }
}
