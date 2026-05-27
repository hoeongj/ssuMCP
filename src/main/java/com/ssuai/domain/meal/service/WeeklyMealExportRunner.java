package com.ssuai.domain.meal.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ssuai.domain.dorm.service.DormMealService;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;

// Export-only one-shot runner. Calls SpringApplication.exit() after writing the
// JSON, so it MUST NOT activate inside the dev/prod API server — both gates
// (export profile + enabled flag) must be set on purpose.
@Component
@Profile("export")
@ConditionalOnProperty(name = "ssuai.meal.export.enabled", havingValue = "true")
class WeeklyMealExportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeeklyMealExportRunner.class);
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final String DEFAULT_OUTPUT_DIRECTORY = "exports/meal";

    private final WeeklyMealService weeklyMealService;
    private final DormMealService dormMealService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final String configuredStartDate;
    private final String configuredOutputPath;

    WeeklyMealExportRunner(
            WeeklyMealService weeklyMealService,
            DormMealService dormMealService,
            ObjectMapper objectMapper,
            ApplicationContext applicationContext,
            @Value("${ssuai.meal.export.start-date:}") String configuredStartDate,
            @Value("${ssuai.meal.export.output:}") String configuredOutputPath
    ) {
        this.weeklyMealService = weeklyMealService;
        this.dormMealService = dormMealService;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.configuredStartDate = configuredStartDate;
        this.configuredOutputPath = configuredOutputPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        LocalDate startDate = resolveStartDate();
        Path outputPath = resolveOutputPath(startDate);

        WeeklyMealResponse response = weeklyMealService.fetchWeeklyMeals(startDate);
        WeeklyMealResponse dormResponse = dormMealService.getThisWeekMeal();
        Path dormOutputPath = resolveDormOutputPath(dormResponse);

        writeJson(outputPath, response);
        log.info("meal weekly export written: path={} startDate={} endDate={} days={}",
                outputPath.toAbsolutePath().normalize(),
                response.startDate(),
                response.endDate(),
                response.days().size());

        writeJson(dormOutputPath, dormResponse);
        log.info("dorm-meal weekly export written: path={} startDate={} endDate={} days={}",
                dormOutputPath.toAbsolutePath().normalize(),
                dormResponse.startDate(),
                dormResponse.endDate(),
                dormResponse.days().size());

        SpringApplication.exit(applicationContext, () -> 0);
    }

    private LocalDate resolveStartDate() {
        if (configuredStartDate == null || configuredStartDate.isBlank()) {
            return LocalDate.now(SEOUL_ZONE);
        }
        return LocalDate.parse(configuredStartDate);
    }

    private Path resolveOutputPath(LocalDate startDate) {
        if (configuredOutputPath != null && !configuredOutputPath.isBlank()) {
            return Path.of(configuredOutputPath);
        }
        LocalDate endDate = startDate.plusDays(6);
        return Path.of(DEFAULT_OUTPUT_DIRECTORY, "weekly-meals-" + startDate + "_" + endDate + ".json");
    }

    private static Path resolveDormOutputPath(WeeklyMealResponse response) {
        return Path.of(DEFAULT_OUTPUT_DIRECTORY,
                "weekly-dorm-meals-" + response.startDate() + "_" + response.endDate() + ".json");
    }

    private void writeJson(Path outputPath, WeeklyMealResponse response) throws Exception {
        Path parent = outputPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), response);
    }
}
