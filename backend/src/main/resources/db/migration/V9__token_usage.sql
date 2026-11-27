-- 全系统 token 流水。scene：1评审 2知识库导入 3知识库重建 4编码规范索引 5其他。call_type：1对话 2向量。
CREATE TABLE token_usage (
    id                  BIGSERIAL PRIMARY KEY,
    scene               SMALLINT NOT NULL,
    call_type           SMALLINT NOT NULL,
    ref_id              BIGINT,
    title               VARCHAR(255),
    model_name          VARCHAR(128),
    prompt_tokens       BIGINT,
    completion_tokens   BIGINT,
    total_tokens        BIGINT,
    error_message       VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_token_usage_created ON token_usage (created_at DESC);
CREATE INDEX idx_token_usage_scene ON token_usage (scene, created_at DESC);
