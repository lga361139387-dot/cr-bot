-- 知识库：迭代版本、产品负责人由人工维护；模块存 GitLab 项目路径。
CREATE TABLE kb_owner (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE kb_iteration (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE
);

ALTER TABLE kb_doc ADD COLUMN iteration_id BIGINT REFERENCES kb_iteration (id);
ALTER TABLE kb_doc ALTER COLUMN module TYPE VARCHAR(255);

CREATE TABLE kb_doc_owner (
    id       BIGSERIAL PRIMARY KEY,
    doc_id   BIGINT NOT NULL REFERENCES kb_doc (id),
    owner_id BIGINT NOT NULL REFERENCES kb_owner (id),
    CONSTRAINT uk_kb_doc_owner UNIQUE (doc_id, owner_id)
);
