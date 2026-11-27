-- 规范文件里的单条规则。enabled：1启用 0禁用。文件级禁用会把同一份规范的规则全部置 0。
CREATE TABLE standard_rule (
    id          BIGSERIAL PRIMARY KEY,
    standard_id BIGINT NOT NULL REFERENCES standard_doc (id),
    code        VARCHAR(16) NOT NULL,
    title       VARCHAR(255) NOT NULL,
    enabled     SMALLINT NOT NULL,
    CONSTRAINT uk_standard_rule UNIQUE (standard_id, code)
);
