-- CRBOT 核心表。状态字段按规格使用字符串编码，与 LLM JSON Schema 对齐。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE repo_subscription (
    id                BIGSERIAL PRIMARY KEY,
    gitlab_project_id BIGINT NOT NULL,
    project_path      VARCHAR(255) NOT NULL,
    branch_pattern    VARCHAR(255) NOT NULL,
    event_types       VARCHAR(64) NOT NULL DEFAULT 'push,merge_request',
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE review_job (
    id                BIGSERIAL PRIMARY KEY,
    gitlab_project_id BIGINT NOT NULL,
    project_path      VARCHAR(255) NOT NULL,
    ref_branch        VARCHAR(255) NOT NULL,
    event_type        VARCHAR(32) NOT NULL,
    mr_iid            INT,
    commit_sha        VARCHAR(64),
    before_sha        VARCHAR(64),
    after_sha         VARCHAR(64),
    pusher_name       VARCHAR(128),
    pusher_email      VARCHAR(255),
    title             VARCHAR(512),
    status            VARCHAR(32) NOT NULL,
    error_message     TEXT,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- push 的 mr_iid 为空。PostgreSQL 唯一约束不把 NULL 视为相同，故用表达式索引落实幂等。
CREATE UNIQUE INDEX uk_review_job_event
    ON review_job (gitlab_project_id, event_type, COALESCE(after_sha, ''), COALESCE(mr_iid, 0));

CREATE INDEX idx_review_job_status_id ON review_job (status, id);
CREATE INDEX idx_review_job_created ON review_job (created_at DESC);

CREATE TABLE review_report (
    id                BIGSERIAL PRIMARY KEY,
    job_id            BIGINT NOT NULL REFERENCES review_job (id),
    summary           TEXT,
    overall_severity  VARCHAR(16),
    business_status   VARCHAR(16),
    business_reason   TEXT,
    raw_json          JSONB NOT NULL,
    markdown          TEXT,
    model_name        VARCHAR(128),
    prompt_version    VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_report_job ON review_report (job_id, id DESC);

CREATE TABLE review_finding (
    id          BIGSERIAL PRIMARY KEY,
    report_id   BIGINT NOT NULL REFERENCES review_report (id) ON DELETE CASCADE,
    category    VARCHAR(32) NOT NULL,
    severity    VARCHAR(16) NOT NULL,
    file_path   VARCHAR(1024),
    start_line  INT,
    end_line    INT,
    title       VARCHAR(512) NOT NULL,
    detail      TEXT,
    suggestion  TEXT,
    refs_json   JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_finding_report ON review_finding (report_id);

CREATE TABLE standard_doc (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    version         VARCHAR(64) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT FALSE,
    source_filename VARCHAR(255),
    content_text    TEXT,
    status          VARCHAR(32) NOT NULL,
    error_message   TEXT,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE kb_doc (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(255) NOT NULL,
    doc_type      VARCHAR(32) NOT NULL,
    product       VARCHAR(128),
    module        VARCHAR(128),
    tags          VARCHAR(512),
    content_text  TEXT,
    status        VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- embedding 维度 1024，须与 embedding 模型一致（如 bge-m3）。
CREATE TABLE kb_chunk (
    id            BIGSERIAL PRIMARY KEY,
    owner_type    VARCHAR(32) NOT NULL,
    owner_id      BIGINT NOT NULL,
    chunk_index   INT NOT NULL,
    content       TEXT NOT NULL,
    metadata_json JSONB,
    embedding     vector(1024),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kb_chunk_owner ON kb_chunk (owner_type, owner_id);
CREATE INDEX idx_kb_chunk_embedding ON kb_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE TABLE datasource_config (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    db_type          VARCHAR(32) NOT NULL,
    jdbc_url         VARCHAR(1024) NOT NULL,
    username         VARCHAR(128) NOT NULL,
    password_enc     TEXT NOT NULL,
    readonly_checked BOOLEAN NOT NULL DEFAULT FALSE,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE app_setting (
    "key"      VARCHAR(64) PRIMARY KEY,
    "value"    TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE prompt_template (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    version    VARCHAR(64) NOT NULL,
    content    TEXT NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prompt_template_name ON prompt_template (name, id DESC);
