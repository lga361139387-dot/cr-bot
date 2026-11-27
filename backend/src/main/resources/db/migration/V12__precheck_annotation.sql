-- 知识库预审：疑点只作审核记录，不替换原文。
ALTER TABLE kb_doc ADD COLUMN source_ext VARCHAR(16);

CREATE TABLE kb_precheck (
    id            BIGSERIAL PRIMARY KEY,
    doc_id        BIGINT       NOT NULL REFERENCES kb_doc (id) ON DELETE CASCADE,
    iteration_id  BIGINT,
    status        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ,
    operator      VARCHAR(64)
);

CREATE INDEX idx_kb_precheck_doc ON kb_precheck (doc_id, id DESC);

CREATE TABLE kb_precheck_doubt (
    id           BIGSERIAL PRIMARY KEY,
    precheck_id  BIGINT       NOT NULL REFERENCES kb_precheck (id) ON DELETE CASCADE,
    doc_id       BIGINT       NOT NULL,
    excerpt      TEXT         NOT NULL,
    question     TEXT         NOT NULL,
    decision     VARCHAR(32),
    note         TEXT,
    operator     VARCHAR(64),
    decided_at   TIMESTAMPTZ
);

CREATE INDEX idx_kb_precheck_doubt_session ON kb_precheck_doubt (precheck_id);

-- 缺陷上的提交人，供后续按人统计。看板本次不计算。
ALTER TABLE review_finding ADD COLUMN pusher_name VARCHAR(128);

-- 人工标注。保存标注默认不重跑；triggered_rereview 仅在用户点击重评审后置位。
CREATE TABLE review_finding_annotation (
    id                  BIGSERIAL PRIMARY KEY,
    job_id              BIGINT       NOT NULL,
    finding_id          BIGINT       NOT NULL REFERENCES review_finding (id),
    iteration_id        BIGINT,
    pusher_name         VARCHAR(128),
    label               VARCHAR(32)  NOT NULL,
    remark              TEXT,
    triggered_rereview  BOOLEAN      NOT NULL DEFAULT FALSE,
    operator            VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_annotation_job ON review_finding_annotation (job_id, finding_id, id DESC);

-- 反馈样本库，后续评审作为 few-shot 参考。
CREATE TABLE review_feedback_sample (
    id             BIGSERIAL PRIMARY KEY,
    annotation_id  BIGINT,
    job_id         BIGINT       NOT NULL,
    finding_id     BIGINT,
    iteration_id   BIGINT,
    pusher_name    VARCHAR(128),
    file_path      VARCHAR(1024),
    start_line     INT,
    category       VARCHAR(32),
    severity       VARCHAR(16),
    title          VARCHAR(512),
    ai_conclusion  TEXT,
    label          VARCHAR(32)  NOT NULL,
    remark         TEXT,
    operator       VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_feedback_created ON review_feedback_sample (id DESC);

-- 重评审轮次。0 表示尚未因人工标注重跑。
ALTER TABLE review_job ADD COLUMN rereview_round INT NOT NULL DEFAULT 0;
-- 以下三列本次只预留：迭代绑定、CI 门禁结果、作者职责角色。
ALTER TABLE review_job ADD COLUMN iteration_id BIGINT;
ALTER TABLE review_job ADD COLUMN gate_status VARCHAR(32);
ALTER TABLE review_job ADD COLUMN author_role VARCHAR(32);

-- 登录角色仍用 app_user.role。duty_role 预留开发 / 负责人 / DBA / 产品。
ALTER TABLE app_user ADD COLUMN duty_role VARCHAR(16);

-- 迭代交付清单预留表，本次不写入。
CREATE TABLE iteration_delivery (
    id            BIGSERIAL PRIMARY KEY,
    iteration_id  BIGINT,
    kind          VARCHAR(32),
    summary       TEXT,
    payload       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
