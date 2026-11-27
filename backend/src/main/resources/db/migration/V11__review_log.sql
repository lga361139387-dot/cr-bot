-- kind：1模型调用 2文件读取 3搜索 4过滤文件
CREATE TABLE review_log (
    id         BIGSERIAL PRIMARY KEY,
    job_id     BIGINT NOT NULL,
    seq        INT NOT NULL,
    kind       SMALLINT NOT NULL,
    title      VARCHAR(255) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_log_job ON review_log (job_id, seq);
