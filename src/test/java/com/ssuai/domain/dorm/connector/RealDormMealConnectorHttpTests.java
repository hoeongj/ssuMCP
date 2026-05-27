package com.ssuai.domain.dorm.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

class RealDormMealConnectorHttpTests {

    private static final Path FIXTURE_PATH =
            Path.of("src/test/resources/fixtures/meal/dorm-week-success.html");

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void fetchThisWeekMealParsesEucKrResponseWithoutManglingHangul() throws Exception {
        enqueueFixtureAsEucKr();
        RealDormMealConnector connector = connectorWithTimeout(5_000);

        WeeklyMealResponse response = connector.fetchThisWeekMeal();

        assertThat(response.days()).hasSize(7);
        assertThat(response.startDate()).isEqualTo(LocalDate.of(2026, 5, 4));
        assertThat(response.days().getFirst().meals().getFirst().menu())
                .contains("순두부찌개", "소이소스돈불고기");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeaders().get("User-Agent")).isEqualTo("ssuAI/0.1 (+akftjdwn@gmail.com)");
        assertThat(request.getHeaders().get("Accept-Language")).isEqualTo("ko-KR,ko;q=0.9");
    }

    @Test
    void fetchThisWeekMealThrowsUnavailableForHttp503() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        RealDormMealConnector connector = connectorWithTimeout(5_000);

        assertThatThrownBy(connector::fetchThisWeekMeal)
                .isInstanceOf(ConnectorUnavailableException.class);
    }

    @Test
    void fetchThisWeekMealThrowsTimeoutWhenServerDoesNotRespond() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .body(fixtureUtf8())
                .build());
        RealDormMealConnector connector = connectorWithTimeout(100);

        assertThatThrownBy(connector::fetchThisWeekMeal)
                .isInstanceOf(ConnectorTimeoutException.class);
    }

    @Test
    void fetchThisWeekMealThrowsParseExceptionForEmptyHtml() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/html; charset=euc-kr")
                .body("<html><body></body></html>")
                .build());
        RealDormMealConnector connector = connectorWithTimeout(5_000);

        assertThatThrownBy(connector::fetchThisWeekMeal)
                .isInstanceOf(ConnectorParseException.class);
    }

    private RealDormMealConnector connectorWithTimeout(int timeoutMs) {
        return new RealDormMealConnector(server.url("/").toString(), 0L, timeoutMs);
    }

    private void enqueueFixtureAsEucKr() throws Exception {
        byte[] eucKrBytes = fixtureUtf8().getBytes(Charset.forName("EUC-KR"));
        Buffer buffer = new Buffer().write(eucKrBytes);
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/html; charset=euc-kr")
                .body(buffer)
                .build());
    }

    private static String fixtureUtf8() throws Exception {
        return Files.readString(FIXTURE_PATH, StandardCharsets.UTF_8);
    }
}
