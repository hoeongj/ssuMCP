package com.ssuai.domain.library.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.dto.BookStatus;
import com.ssuai.domain.library.dto.LibraryBook;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;

/**
 * Deterministic fixture for `ssuai.connector.library-book=mock` (default).
 * Matching is a case-insensitive substring search across title/author/publication,
 * so the mock behaves like a tiny in-memory Pyxis. Replaced by the real
 * connector once `ssuai.connector.library-book=real` is set.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.library-book", havingValue = "mock", matchIfMissing = true)
public class MockLibraryBookConnector implements LibraryBookConnector {

    private static final List<LibraryBook> FIXTURE = List.of(
            new LibraryBook(5006619L, "파이썬 : 기초와 활용", "한정란",
                    "파주 : 21세기사, 2023", "9791168330702",
                    "https://image.aladin.co.kr/product/31222/41/cover/k532831252_1.jpg",
                    "005.133P9 한7362파", "중앙도서관", BookStatus.AVAILABLE),
            new LibraryBook(5000719L, "(두근두근) 파이썬", "천인국",
                    "파주 : 생능, 2023", "9788970506562",
                    "https://image.aladin.co.kr/product/30773/95/cover/897050656x_1.jpg",
                    "005.133P9 천6921파2", "중앙도서관", BookStatus.AVAILABLE),
            new LibraryBook(4986583L, "(새내기) 파이썬", "천인국",
                    "파주 : 생능, 2022", "9788970505558",
                    "https://image.aladin.co.kr/product/29687/37/cover/8970505555_2.jpg",
                    "005.133P9 천6921파생", "중앙도서관", BookStatus.CHECKED_OUT),
            new LibraryBook(4819704L, "(컴퓨팅 사고를 위한) 파이썬", "한선관",
                    "파주 : 생능, 2019", "9788970509723",
                    "https://image.aladin.co.kr/product/18454/56/cover/8970509720_1.jpg",
                    "005.133P9 한5321파", "중앙도서관", BookStatus.AVAILABLE),
            new LibraryBook(4532517L, "모두의 데이터 분석 with 파이썬", "송석리",
                    "서울 : 길벗, 2019", "9791160506181", null,
                    "005.133P9 송2154모", "중앙도서관", BookStatus.AVAILABLE),
            new LibraryBook(4901230L, "이펙티브 자바 (Effective Java)", "조슈아 블로크",
                    "서울 : 인사이트, 2018", "9788966262281",
                    "https://image.aladin.co.kr/product/17331/72/cover/8966262287_1.jpg",
                    "005.133J2 블3211이", "중앙도서관", BookStatus.AVAILABLE),
            new LibraryBook(4912455L, "자바의 정석", "남궁성",
                    "서울 : 도우출판, 2016", "9788994492032", null,
                    "005.133J2 남2233자", "중앙도서관", BookStatus.CHECKED_OUT),
            new LibraryBook(4870122L, "클린 코드 (Clean Code)", "로버트 C. 마틴",
                    "서울 : 인사이트, 2013", "9788966260959",
                    "https://image.aladin.co.kr/product/304/16/cover/8966260950_1.jpg",
                    "005.1 마8121클", "중앙도서관", BookStatus.AVAILABLE),
            new LibraryBook(4985003L, "운영체제 공룡책", "에이브러햄 실버샤츠",
                    "서울 : 홍릉과학출판사, 2020", "9788970889771", null,
                    "005.43 실8512운", "중앙도서관", BookStatus.AVAILABLE),
            new LibraryBook(4801577L, "데이터베이스 시스템", "이상호",
                    "서울 : 정익사, 2017", "9788956083759", null,
                    "005.74 이3812데", "중앙도서관", BookStatus.CHECKED_OUT)
    );

    @Override
    public LibraryBookSearchResponse search(String query, int page, int size) {
        String needle = query == null ? "" : query.trim().toLowerCase();
        List<LibraryBook> matched = FIXTURE.stream()
                .filter(book -> matches(book, needle))
                .toList();

        int total = matched.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<LibraryBook> page0 = matched.subList(from, to);

        return new LibraryBookSearchResponse(total, page, size, page0);
    }

    private boolean matches(LibraryBook book, String needle) {
        if (needle.isBlank()) {
            return true;
        }
        return containsIgnoreCase(book.title(), needle)
                || containsIgnoreCase(book.author(), needle)
                || containsIgnoreCase(book.publication(), needle);
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }
}
