CREATE TABLE notice_index (
    id              BIGSERIAL PRIMARY KEY,
    category        VARCHAR(64)                   NOT NULL DEFAULT '',
    title           TEXT                          NOT NULL DEFAULT '',
    link            TEXT                          NOT NULL,
    posted_date     DATE,
    department      VARCHAR(128)                  NOT NULL DEFAULT '',
    status          VARCHAR(32)                   NOT NULL DEFAULT '',
    indexed_at      TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    source          VARCHAR(16)                   NOT NULL DEFAULT 'scatch',
    CONSTRAINT uq_notice_index_link UNIQUE (link)
);

CREATE INDEX idx_notice_index_posted_date ON notice_index(posted_date DESC NULLS LAST);
CREATE INDEX idx_notice_index_category    ON notice_index(category);
