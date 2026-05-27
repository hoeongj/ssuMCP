package com.ssuai.domain.library.connector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.dto.LibraryBookSearchResponse;

class MockLibraryBookConnectorTests {

    private final MockLibraryBookConnector connector = new MockLibraryBookConnector();

    @Test
    void sameQueryReturnsSameResultsDeterministically() {
        LibraryBookSearchResponse first = connector.search("파이썬", 0, 10);
        LibraryBookSearchResponse second = connector.search("파이썬", 0, 10);

        assertThat(second).isEqualTo(first);
        assertThat(first.items()).isNotEmpty();
        assertThat(first.items()).allSatisfy(book -> assertThat(book.title()).isNotBlank());
    }

    @Test
    void matchesTitleAuthorAndPublication() {
        LibraryBookSearchResponse byTitle = connector.search("파이썬", 0, 20);
        LibraryBookSearchResponse byAuthor = connector.search("천인국", 0, 20);
        LibraryBookSearchResponse byPublisher = connector.search("인사이트", 0, 20);

        assertThat(byTitle.items()).extracting(book -> book.title().toLowerCase())
                .anyMatch(t -> t.contains("파이썬"));
        assertThat(byAuthor.items()).extracting(book -> book.author())
                .contains("천인국");
        assertThat(byPublisher.items()).extracting(book -> book.publication())
                .anyMatch(p -> p.contains("인사이트"));
    }

    @Test
    void caseInsensitiveSearch() {
        LibraryBookSearchResponse lower = connector.search("clean code", 0, 10);
        LibraryBookSearchResponse upper = connector.search("CLEAN CODE", 0, 10);

        assertThat(lower.total()).isEqualTo(upper.total()).isPositive();
    }

    @Test
    void paginationSlicesTheTotal() {
        LibraryBookSearchResponse page0 = connector.search("", 0, 3);
        LibraryBookSearchResponse page1 = connector.search("", 1, 3);

        assertThat(page0.items()).hasSize(3);
        assertThat(page1.items()).hasSizeBetween(1, 3);
        assertThat(page1.items()).doesNotContainAnyElementsOf(page0.items());
        assertThat(page0.total()).isEqualTo(page1.total());
    }

    @Test
    void unmatchedQueryReturnsEmpty() {
        LibraryBookSearchResponse response = connector.search("zzz-no-such-book-keyword-xyzzy", 0, 10);

        assertThat(response.total()).isZero();
        assertThat(response.items()).isEmpty();
    }
}
