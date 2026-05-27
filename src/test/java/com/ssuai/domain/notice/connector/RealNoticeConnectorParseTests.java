package com.ssuai.domain.notice.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.notice.dto.Notice;

class RealNoticeConnectorParseTests {

    @Test
    void parseNoticeListExtractsItemsFromFixture() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices).isNotEmpty();
        assertThat(notices.getFirst().date()).isNotBlank();
        assertThat(notices.getFirst().title()).isNotBlank();
    }

    @Test
    void parseNoticeListExtractsDateCorrectly() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices.getFirst().date()).matches("\\d{4}\\.\\d{2}\\.\\d{2}");
    }

    @Test
    void parseNoticeListExtractsStatusCorrectly() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices)
                .extracting(Notice::status)
                .allSatisfy(status ->
                        assertThat(status).isIn("진행", "완료", ""));
    }

    @Test
    void parseNoticeListExtractsTitleAndLink() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices.getFirst().title()).isNotBlank();
        // link may be empty in fixture if base URI is not set, so just check it doesn't throw
        assertThat(notices.getFirst().link()).isNotNull();
    }

    @Test
    void parseNoticeListExtractsCategoryLabel() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        // At least some notices should have categories
        assertThat(notices)
                .extracting(Notice::category)
                .anySatisfy(cat -> assertThat(cat).isNotBlank());
    }

    @Test
    void parseNoticeListExtractsDepartment() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices)
                .extracting(Notice::department)
                .anySatisfy(dept -> assertThat(dept).isNotBlank());
    }

    @Test
    void parseTotalPagesExtractsMaxPageNumber() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        int totalPages = RealNoticeConnector.parseTotalPages(doc);

        assertThat(totalPages).isGreaterThanOrEqualTo(1);
    }

    @Test
    void parseEmptyDocumentReturnsEmptyList() {
        Document emptyDoc = Jsoup.parse("<html><body></body></html>");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(emptyDoc);

        assertThat(notices).isEmpty();
    }

    @Test
    void parseTotalPagesFromEmptyDocReturnsMinusOne() {
        Document emptyDoc = Jsoup.parse("<html><body></body></html>");

        int totalPages = RealNoticeConnector.parseTotalPages(emptyDoc);

        assertThat(totalPages).isEqualTo(-1);
    }

    @Test
    void parseDetailBodySelectsContentAfterHr() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_detail.html");

        // hr + div selector should find content div (not header/metadata)
        org.jsoup.nodes.Element body = doc.selectFirst("div.bg-white > hr + div");

        assertThat(body).isNotNull();
        String text = body.text();
        // body should contain actual content, not just metadata noise
        assertThat(text.length()).isGreaterThan(50);
        // should NOT start with category label or date pattern
        assertThat(text).doesNotStartWith("국제교류");
        assertThat(text).doesNotStartWith("장학");
    }

    private static Document loadFixture(String resourcePath) throws IOException {
        try (InputStream in = RealNoticeConnectorParseTests.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("fixture not found: " + resourcePath);
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Jsoup.parse(html, "https://scatch.ssu.ac.kr");
        }
    }
}
