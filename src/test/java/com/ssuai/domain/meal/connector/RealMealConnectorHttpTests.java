package com.ssuai.domain.meal.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

class RealMealConnectorHttpTests {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 6);
    private static final Path FIXTURE_PATH = Path.of("src/test/resources/fixtures/meal/today-success.html");
    private static final Path DODAM_CHILDRENS_DAY_FIXTURE_PATH =
            Path.of("src/test/resources/fixtures/meal/dodam-childrens-day-closure.html");

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
    void fetchMealParsesSuccessfulResponse() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body(fixture())
                .build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        MealResponse response = connector.fetchMeal(DATE, MealRestaurant.STUDENT);

        assertThat(response.meals())
                .hasSize(4)
                .extracting(MealItem::restaurant)
                .containsOnly("학생식당");
        assertThat(response.closures()).isEmpty();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getTarget()).isEqualTo("/m/m_req/m_menu.php?rcd=1&sdt=20260506");
        assertThat(request.getHeaders().get("User-Agent")).isEqualTo("ssuAI/0.1 (+akftjdwn@gmail.com)");
        assertThat(request.getHeaders().get("Accept-Language")).isEqualTo("ko-KR,ko;q=0.9");
    }

    @Test
    void fetchMealUsesRestaurantCodeInQueryString() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body(closedBody())
                .build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        connector.fetchMeal(DATE, MealRestaurant.FACULTY_LOUNGE);

        assertThat(server.takeRequest().getTarget())
                .isEqualTo("/m/m_req/m_menu.php?rcd=7&sdt=20260506");
    }

    @Test
    void fetchMealThrowsUnavailableForHttp503() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        assertThatThrownBy(() -> connector.fetchMeal(DATE, MealRestaurant.STUDENT))
                .isInstanceOf(ConnectorUnavailableException.class);
    }

    @Test
    void fetchMealThrowsTimeoutWhenServerDoesNotRespond() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .body(fixtureUnchecked())
                .build());
        RealMealConnector connector = connectorWithTimeout(100);

        assertThatThrownBy(() -> connector.fetchMeal(DATE, MealRestaurant.STUDENT))
                .isInstanceOf(ConnectorTimeoutException.class);
    }

    @Test
    void fetchMealThrowsParseExceptionForEmptyHtml() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body("<html><body></body></html>")
                .build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        assertThatThrownBy(() -> connector.fetchMeal(DATE, MealRestaurant.STUDENT))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void fetchMealReturnsClosureWhenRestaurantIsClosed() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body(closedBody())
                .build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        MealResponse response = connector.fetchMeal(DATE, MealRestaurant.SNACK);

        assertThat(response.meals()).isEmpty();
        assertThat(response.closures())
                .singleElement()
                .satisfies(closure -> {
                    assertThat(closure.restaurant()).isEqualTo("스낵코너");
                    assertThat(closure.reason()).isEqualTo("오늘은 쉽니다.");
                });
    }

    @Test
    void fetchMealParsesGenericMenuRowsAsAllDay() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body("""
                        <table>
                            <tr>
                                <td class="menu_nm">메뉴</td>
                                <td class="menu_list">추억의도시락 - 5.0<br>치킨마요덮밥 - 5.0</td>
                            </tr>
                        </table>
                        """)
                .build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        MealResponse response = connector.fetchMeal(DATE, MealRestaurant.SNACK);

        assertThat(response.meals())
                .singleElement()
                .satisfies(meal -> {
                    assertThat(meal.restaurant()).isEqualTo("스낵코너");
                    assertThat(meal.type()).isEqualTo(MealType.ALL_DAY);
                    assertThat(meal.corner()).isEqualTo("메뉴");
                    assertThat(meal.menu()).containsExactly("추억의도시락", "치킨마요덮밥");
                });
        assertThat(response.closures()).isEmpty();
    }

    @Test
    void fetchMealReturnsClosureForHolidayNoticeInsideMenuRow() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body(dodamChildrensDayFixtureUnchecked())
                .build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        MealResponse response = connector.fetchMeal(DATE, MealRestaurant.DODAM);

        assertThat(response.meals()).isEmpty();
        assertThat(response.closures())
                .singleElement()
                .satisfies(closure -> {
                    assertThat(closure.restaurant()).isEqualTo("숭실도담식당");
                    assertThat(closure.reason()).contains("어린이날");
                });
    }

    @Test
    void fetchMealPreservesClosureRowsWhenOtherRowsHaveMeals() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body("""
                        <table>
                            <tr>
                                <td class="menu_nm">중식1</td>
                                <td class="menu_list">쌀밥<br>미역국</td>
                            </tr>
                            <tr>
                                <td class="menu_nm">석식1</td>
                                <td class="menu_list">오늘은 쉽니다.</td>
                            </tr>
                        </table>
                        """)
                .build());
        RealMealConnector connector = connectorWithTimeout(5_000);

        MealResponse response = connector.fetchMeal(DATE, MealRestaurant.STUDENT);

        assertThat(response.meals())
                .singleElement()
                .satisfies(meal -> {
                    assertThat(meal.corner()).isEqualTo("중식1");
                    assertThat(meal.menu()).containsExactly("쌀밥", "미역국");
                });
        assertThat(response.closures())
                .singleElement()
                .satisfies(closure -> {
                    assertThat(closure.restaurant()).isEqualTo("학생식당");
                    assertThat(closure.reason()).isEqualTo("오늘은 쉽니다.");
                });
    }

    @Test
    void fetchMealRateLimitsRepeatedCallsForSameRestaurantCode() throws Exception {
        server.enqueue(successResponse());
        server.enqueue(successResponse());
        RealMealConnector connector = new RealMealConnector(
                server.url("/m/m_req/m_menu.php").toString(), 200L, 5_000);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<MealResponse> first = executorService.submit(() -> fetchAfterStart(connector, start, MealRestaurant.STUDENT));
            Future<MealResponse> second = executorService.submit(() -> fetchAfterStart(connector, start, MealRestaurant.STUDENT));

            long startedAt = System.nanoTime();
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(elapsedMs).isGreaterThanOrEqualTo(150L);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void fetchMealDoesNotRateLimitDifferentRestaurantCodesAgainstEachOther() throws Exception {
        server.enqueue(successResponse());
        server.enqueue(successResponse());
        RealMealConnector connector = new RealMealConnector(
                server.url("/m/m_req/m_menu.php").toString(), 1_000L, 5_000);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<MealResponse> student = executorService.submit(() -> fetchAfterStart(connector, start, MealRestaurant.STUDENT));
            Future<MealResponse> dodam = executorService.submit(() -> fetchAfterStart(connector, start, MealRestaurant.DODAM));

            long startedAt = System.nanoTime();
            start.countDown();
            student.get(5, TimeUnit.SECONDS);
            dodam.get(5, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(elapsedMs).isLessThan(900L);
        } finally {
            executorService.shutdownNow();
        }
    }

    private RealMealConnector connectorWithTimeout(int timeoutMs) {
        return new RealMealConnector(server.url("/m/m_req/m_menu.php").toString(), 0L, timeoutMs);
    }

    private static MealResponse fetchAfterStart(
            RealMealConnector connector,
            CountDownLatch start,
            MealRestaurant restaurant
    ) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return connector.fetchMeal(DATE, restaurant);
    }

    private static MockResponse successResponse() {
        return new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body(fixtureUnchecked())
                .build();
    }

    private static String fixture() throws Exception {
        return Files.readString(FIXTURE_PATH, StandardCharsets.UTF_8);
    }

    private static String closedBody() {
        return """
                <table>
                    <tr><td colspan="2">일 월 화 수 목 금 토</td></tr>
                    <tr><td colspan="2">오늘은 쉽니다.</td></tr>
                </table>
                """;
    }

    private static String fixtureUnchecked() {
        try {
            return fixture();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String dodamChildrensDayFixtureUnchecked() {
        try {
            return Files.readString(DODAM_CHILDRENS_DAY_FIXTURE_PATH, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
