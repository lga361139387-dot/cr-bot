-- 评审日志按重跑批次保留。round：0 首次，1/2/3 为第几次重跑。
ALTER TABLE review_log ADD COLUMN round INT NOT NULL DEFAULT 0;

-- 0 未作废 1 已作废。作废只打标，不删报告。
ALTER TABLE review_job ADD COLUMN voided SMALLINT NOT NULL DEFAULT 0;

-- 标注对话。role：1用户 2助手。mode：1完整重跑 2只调整结论。need_change/confirmed：0否 1是。
CREATE TABLE review_feedback_chat (
    id           BIGSERIAL PRIMARY KEY,
    job_id       BIGINT NOT NULL,
    role         SMALLINT NOT NULL,
    content      TEXT NOT NULL,
    need_change  SMALLINT NOT NULL DEFAULT 0,
    mode         SMALLINT,
    confirmed    SMALLINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_feedback_chat_job ON review_feedback_chat (job_id, id);
