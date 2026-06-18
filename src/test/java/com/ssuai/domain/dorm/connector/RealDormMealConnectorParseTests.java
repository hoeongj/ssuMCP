package com.ssuai.domain.dorm.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.dto.MealClosure;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.exception.ConnectorParseException;

class RealDormMealConnectorParseTests {

    private static final Path FIXTURE_PATH =
            Path.of("src/test/resources/fixtures/meal/dorm-week-success.html");

    @Test
    void parseExtractsAllSevenDaysFromFixture() throws Exception {
        Document document = Jsoup.parse(FIXTURE_PATH.toFile(), StandardCharsets.UTF_8.name());

        WeeklyMealResponse response = RealDormMealConnector.parse(document);

        assertThat(response.days()).hasSize(7);
        assertThat(response.startDate()).isEqualTo(LocalDate.of(2026, 5, 4));
        assertThat(response.endDate()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(response.days())
                .extracting(MealResponse::date)
                .containsExactly(
                        LocalDate.of(2026, 5, 4),
                        LocalDate.of(2026, 5, 5),
                        LocalDate.of(2026, 5, 6),
                        LocalDate.of(2026, 5, 7),
                        LocalDate.of(2026, 5, 8),
                        LocalDate.of(2026, 5, 9),
                        LocalDate.of(2026, 5, 10));
    }

    @Test
    void parseMarksBreakfastAsClosureForEveryDayInFixture() throws Exception {
        Document document = Jsoup.parse(FIXTURE_PATH.toFile(), StandardCharsets.UTF_8.name());

        WeeklyMealResponse response = RealDormMealConnector.parse(document);

        assertThat(response.days())
                .allSatisfy(day -> {
                    assertThat(day.closures())
                            .singleElement()
                            .satisfies(closure -> {
                                assertThat(closure.restaurant()).isEqualTo(RealDormMealConnector.RESTAURANT);
                                assertThat(closure.reason()).isEqualTo("조식 미운영");
                            });
                    assertThat(day.meals())
                            .hasSize(2)
                            .extracting(MealItem::type)
                            .containsExactly(MealType.LUNCH, MealType.DINNER);
                });
    }

    @Test
    void parseSplitsMenuOnBrTagsAndPreservesOrder() throws Exception {
        Document document = Jsoup.parse(FIXTURE_PATH.toFile(), StandardCharsets.UTF_8.name());

        WeeklyMealResponse response = RealDormMealConnector.parse(document);

        MealResponse monday = response.days().getFirst();
        assertThat(monday.meals().get(0).menu())
                .containsExactly(
                        "순두부찌개", "쌀밥&흑미밥", "소이소스돈불고기", "치킨너겟강정",
                        "모듬상추쌈&쌈장", "깍두기", "요구르트");
        assertThat(monday.meals().get(1).menu())
                .containsExactly(
                        "치쿠와우동(大)", "쌀밥", "꼬마돈까스", "꿀고구마맛탕",
                        "무말랭이무침", "배추김치", "요구르트");
    }

    @Test
    void parseTreatsClosureKeywordsPerCellAndPreservesReasonText() {
        String html = """
                <table class="boxstyle02">
                    <tbody>
                        <tr>
                            <th><a>2026-05-05 (화)</a></th>
                            <td>공휴일 미운영</td>
                            <td>어린이날 휴무</td>
                            <td>운영하지 않습니다</td>
                            <td></td>
                        </tr>
                    </tbody>
                </table>
                """;
        Document document = Jsoup.parse(html);

        WeeklyMealResponse response = RealDormMealConnector.parse(document);

        assertThat(response.days())
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.meals()).isEmpty();
                    assertThat(day.closures())
                            .extracting(MealClosure::reason)
                            .containsExactly(
                                    "조식 공휴일 미운영",
                                    "중식 어린이날 휴무",
                                    "석식 운영하지 않습니다");
                });
    }

    @Test
    void parseDropsPunctuationOnlyPlaceholderMenuLines() {
        // The dorm page fills empty meal slots with placeholder characters ("."/"-"); those
        // must not be surfaced as menu items (external review — dummy "." menu).
        String html = """
                <table class="boxstyle02">
                    <tbody>
                        <tr>
                            <th><a>2026-05-06 (수)</a></th>
                            <td>.</td>
                            <td>비빔밥<br>.<br>된장국<br>-</td>
                            <td>-</td>
                        </tr>
                    </tbody>
                </table>
                """;
        Document document = Jsoup.parse(html);

        WeeklyMealResponse response = RealDormMealConnector.parse(document);

        assertThat(response.days())
                .singleElement()
                .satisfies(day -> {
                    // Breakfast "." and dinner "-" are placeholder-only → no meal, no closure.
                    assertThat(day.meals())
                            .singleElement()
                            .satisfies(meal -> {
                                assertThat(meal.type()).isEqualTo(MealType.LUNCH);
                                assertThat(meal.menu()).containsExactly("비빔밥", "된장국");
                            });
                    assertThat(day.closures()).isEmpty();
                });
    }

    @Test
    void parseThrowsWhenNoMenuTableIsPresent() {
        Document document = Jsoup.parse("<html><body><p>nothing here</p></body></html>");

        assertThatThrownBy(() -> RealDormMealConnector.parse(document))
                .isInstanceOf(ConnectorParseException.class);
    }
}
