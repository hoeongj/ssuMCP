package com.ssuai.domain.notice.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Local index of scatch.ssu.ac.kr notices. Populated by
 * {@link com.ssuai.domain.notice.service.NoticeIndexingService} on a
 * scheduled crawl; queried by {@link com.ssuai.domain.notice.service.NoticeService}
 * for keyword search so that results are ranked by date rather than
 * the scatch search engine's relevance heuristic (which surfaces 2022
 * postings ahead of current notices).
 */
@Entity
@Table(name = "notice_index")
public class NoticeIndexEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String category = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title = "";

    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String link = "";

    @Column(name = "posted_date")
    private LocalDate postedDate;

    @Column(nullable = false, length = 128)
    private String department = "";

    @Column(nullable = false, length = 32)
    private String status = "";

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    @Column(nullable = false, length = 16)
    private String source = "scatch";

    protected NoticeIndexEntry() {
        // JPA
    }

    public NoticeIndexEntry(
            String category,
            String title,
            String link,
            LocalDate postedDate,
            String department,
            String status,
            Instant indexedAt) {
        this.category = category != null ? category : "";
        this.title = title != null ? title : "";
        this.link = link != null ? link : "";
        this.postedDate = postedDate;
        this.department = department != null ? department : "";
        this.status = status != null ? status : "";
        this.indexedAt = indexedAt;
    }

    public Long getId() { return id; }
    public String getCategory() { return category; }
    public String getTitle() { return title; }
    public String getLink() { return link; }
    public LocalDate getPostedDate() { return postedDate; }
    public String getDepartment() { return department; }
    public String getStatus() { return status; }
    public Instant getIndexedAt() { return indexedAt; }
    public String getSource() { return source; }

    public void setCategory(String category) { this.category = category != null ? category : ""; }
    public void setTitle(String title) { this.title = title != null ? title : ""; }
    public void setLink(String link) { this.link = link != null ? link : ""; }
    public void setPostedDate(LocalDate postedDate) { this.postedDate = postedDate; }
    public void setDepartment(String department) { this.department = department != null ? department : ""; }
    public void setStatus(String status) { this.status = status != null ? status : ""; }
    public void setIndexedAt(Instant indexedAt) { this.indexedAt = indexedAt; }
}
