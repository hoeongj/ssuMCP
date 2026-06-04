package com.ssuai.domain.notice.connector;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.notice")
public class NoticeConnectorProperties {

    private String baseUrl = "https://scatch.ssu.ac.kr";
    private Duration cacheTtl = Duration.ofMinutes(5);
    private Duration timeout = Duration.ofSeconds(8);
    private int maxPageSize = 20;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    private Selectors selectors = new Selectors();

    public Selectors getSelectors() {
        return selectors;
    }

    public void setSelectors(Selectors selectors) {
        this.selectors = selectors;
    }

    public static class Selectors {
        private String listItem = "ul.notice-lists > li:not(.notice_head)";
        private String date = "div.notice_col1 div.h2";
        private String status = "div.notice_col2 span.tag";
        private String titleLink = "div.notice_col3 > a";
        private String titleText = "span.d-inline-blcok.m-pt-5";
        private String categoryLabel = "span.label";
        private String department = "div.notice_col4";
        private String pagination = "nav.board-pagination a.page-numbers";
        private String detailBody = "div.bg-white > hr + div";

        public String getListItem() { return listItem; }
        public void setListItem(String listItem) { this.listItem = listItem; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getTitleLink() { return titleLink; }
        public void setTitleLink(String titleLink) { this.titleLink = titleLink; }
        public String getTitleText() { return titleText; }
        public void setTitleText(String titleText) { this.titleText = titleText; }
        public String getCategoryLabel() { return categoryLabel; }
        public void setCategoryLabel(String categoryLabel) { this.categoryLabel = categoryLabel; }
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }
        public String getPagination() { return pagination; }
        public void setPagination(String pagination) { this.pagination = pagination; }
        public String getDetailBody() { return detailBody; }
        public void setDetailBody(String detailBody) { this.detailBody = detailBody; }
    }
}
