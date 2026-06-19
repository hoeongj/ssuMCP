package com.ssuai.domain.library.connector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Thumbnail relative→absolute URL handling (⑦ misc robustness, ADR 0046).
 */
class LibraryBookThumbnailUrlTests {

    private static final String BASE = "https://oasis.ssu.ac.kr";

    @Test
    void absoluteUrl_prefixesRelative_keepsAbsolute_handlesNullAndTrailingSlash() {
        assertThat(RealLibraryBookConnector.absoluteUrl("/thumb/x.jpg", BASE))
                .isEqualTo("https://oasis.ssu.ac.kr/thumb/x.jpg");
        assertThat(RealLibraryBookConnector.absoluteUrl("thumb/x.jpg", BASE))
                .isEqualTo("https://oasis.ssu.ac.kr/thumb/x.jpg");
        assertThat(RealLibraryBookConnector.absoluteUrl("https://cdn.example/x.jpg", BASE))
                .isEqualTo("https://cdn.example/x.jpg");
        assertThat(RealLibraryBookConnector.absoluteUrl("http://cdn.example/x.jpg", BASE))
                .isEqualTo("http://cdn.example/x.jpg");
        assertThat(RealLibraryBookConnector.absoluteUrl(null, BASE)).isNull();
        assertThat(RealLibraryBookConnector.absoluteUrl("", BASE)).isEmpty();
        assertThat(RealLibraryBookConnector.absoluteUrl("/x", "https://oasis.ssu.ac.kr/"))
                .isEqualTo("https://oasis.ssu.ac.kr/x");
    }
}
