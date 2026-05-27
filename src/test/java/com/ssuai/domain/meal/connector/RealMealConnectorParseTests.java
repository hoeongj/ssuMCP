package com.ssuai.domain.meal.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.global.exception.ConnectorParseException;

class RealMealConnectorParseTests {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 6);
    private static final Path FIXTURE_PATH = Path.of("src/test/resources/fixtures/meal/today-success.html");
    private static final Path DODAM_FIXTURE_PATH = Path.of("src/test/resources/fixtures/meal/dodam-success.html");

    @Test
    void parseReturnsMealsFromFixture() throws Exception {
        Document document = Jsoup.parse(FIXTURE_PATH.toFile(), StandardCharsets.UTF_8.name());

        MealResponse response = RealMealConnector.parse(DATE, "학생식당", document);

        assertThat(response.date()).isEqualTo(DATE);
        assertThat(response.meals())
                .extracting(MealItem::restaurant)
                .containsExactly("학생식당", "학생식당", "학생식당", "학생식당");
        assertThat(response.meals())
                .hasSize(4)
                .extracting(MealItem::type)
                .containsExactly(MealType.LUNCH, MealType.LUNCH, MealType.LUNCH, MealType.DINNER);
        assertThat(response.meals())
                .extracting(MealItem::corner)
                .containsExactly("중식1", "중식2", "중식3", "석식1");
        assertThat(response.meals())
                .allSatisfy(meal -> assertThat(meal.menu()).isNotEmpty());
        assertThat(response.meals().getFirst().menu())
                .containsExactly("소고기만두전골", "깐풍어묵볶음", "백미밥", "깍두기");
    }

    @Test
    void parseThrowsWhenMenuRowsAreMissing() {
        Document document = Jsoup.parse("<html><body></body></html>");

        assertThatThrownBy(() -> RealMealConnector.parse(DATE, "학생식당", document))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void parseReturnsNestedDodamMenuItems() throws Exception {
        Document document = Jsoup.parse(DODAM_FIXTURE_PATH.toFile(), StandardCharsets.UTF_8.name());

        MealResponse response = RealMealConnector.parse(DATE, "숭실도담식당", document);

        assertThat(response.meals()).hasSize(1);
        assertThat(response.meals().getFirst().restaurant()).isEqualTo("숭실도담식당");
        assertThat(response.meals().getFirst().menu())
                .containsExactly(
                        "매실우불고기",
                        "꽈리감자조림",
                        "브로콜리&다시마&초장",
                        "북어국",
                        "배추김치",
                        "잡곡밥"
                );
    }
}
