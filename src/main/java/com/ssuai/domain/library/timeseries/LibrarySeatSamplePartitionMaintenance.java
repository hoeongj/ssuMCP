package com.ssuai.domain.library.timeseries;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.redis.LibrarySchedulerLeadership;

@Component
public class LibrarySeatSamplePartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatSamplePartitionMaintenance.class);
    private static final Pattern PARTITION_NAME = Pattern.compile("^library_seat_samples_(\\d{6})$");
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbcTemplate;
    private final LibrarySeatSampleProperties properties;
    private final boolean postgres;
    private final Clock clock;
    private final LibrarySchedulerLeadership schedulerLeadership;

    @Autowired
    public LibrarySeatSamplePartitionMaintenance(
            DataSource dataSource,
            LibrarySeatSampleProperties properties,
            Environment environment,
            LibrarySchedulerLeadership schedulerLeadership,
            Clock clock) {
        this(
                new JdbcTemplate(dataSource),
                properties,
                environment.getProperty("spring.datasource.url", ""),
                schedulerLeadership,
                clock);
    }

    LibrarySeatSamplePartitionMaintenance(
            JdbcTemplate jdbcTemplate,
            LibrarySeatSampleProperties properties,
            String datasourceUrl,
            LibrarySchedulerLeadership schedulerLeadership,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.postgres = isPostgresUrl(datasourceUrl);
        this.schedulerLeadership = schedulerLeadership;
        this.clock = clock;
    }

    LibrarySeatSamplePartitionMaintenance(
            JdbcTemplate jdbcTemplate,
            LibrarySeatSampleProperties properties,
            String datasourceUrl,
            Clock clock) {
        this(jdbcTemplate, properties, datasourceUrl, LibrarySchedulerLeadership.noop(), clock);
    }

    @PostConstruct
    public void maintainOnBoot() {
        maintainSafely("boot");
    }

    @Scheduled(cron = "0 23 3 * * *", zone = "UTC")
    public void maintainScheduled() {
        maintainSafely("scheduled");
    }

    public void maintain() {
        if (!postgres || !properties.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        List<String> existingPartitions = existingPartitionNames();
        for (String sql : maintenanceSql(now, properties.getRetentionDays(), existingPartitions)) {
            jdbcTemplate.execute(sql);
        }
    }

    private void maintainSafely(String source) {
        schedulerLeadership.runIfLeader("seat-partition-maintenance", () -> maintainSafelyWithLock(source));
    }

    private void maintainSafelyWithLock(String source) {
        try {
            maintain();
        } catch (RuntimeException exception) {
            log.warn("library seat sample partition maintenance failed: source={}", source, exception);
        }
    }

    List<String> maintenanceSql(Instant now, int retentionDays, Collection<String> existingPartitionNames) {
        YearMonth currentMonth = YearMonth.from(now.atZone(ZoneOffset.UTC));
        List<String> statements = new ArrayList<>();
        statements.add(createPartitionSql(currentMonth));
        statements.add(createPartitionSql(currentMonth.plusMonths(1)));

        Instant cutoff = now.minusSeconds(retentionDays * 86_400L);
        for (String partitionName : existingPartitionNames) {
            parsePartitionMonth(partitionName)
                    .filter(month -> !upperBound(month).isAfter(cutoff))
                    .map(LibrarySeatSamplePartitionMaintenance::dropPartitionSql)
                    .ifPresent(statements::add);
        }
        return statements;
    }

    private List<String> existingPartitionNames() {
        return jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'library_seat_samples'
                  AND c.relname LIKE 'library_seat_samples_%'
                """, String.class);
    }

    static String createPartitionSql(YearMonth month) {
        YearMonth nextMonth = month.plusMonths(1);
        return "CREATE TABLE IF NOT EXISTS " + partitionName(month)
                + " PARTITION OF library_seat_samples FOR VALUES FROM ('" + lowerBoundText(month)
                + "') TO ('" + lowerBoundText(nextMonth) + "')";
    }

    static String dropPartitionSql(YearMonth month) {
        return "DROP TABLE IF EXISTS " + partitionName(month);
    }

    static Optional<YearMonth> parsePartitionMonth(String partitionName) {
        if (partitionName == null) {
            return Optional.empty();
        }
        Matcher matcher = PARTITION_NAME.matcher(partitionName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(YearMonth.parse(matcher.group(1), PARTITION_SUFFIX));
    }

    static boolean isPostgresUrl(String datasourceUrl) {
        return datasourceUrl != null
                && datasourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:");
    }

    private static Instant upperBound(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String partitionName(YearMonth month) {
        return "library_seat_samples_" + month.format(PARTITION_SUFFIX);
    }

    private static String lowerBoundText(YearMonth month) {
        LocalDate firstDay = month.atDay(1);
        return firstDay + "T00:00:00Z";
    }
}
