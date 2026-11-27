-- 人员角色：1产品负责人 2开发人员 3测试人员。同一角色下姓名不重复。
ALTER TABLE kb_owner ADD COLUMN role SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE kb_owner DROP CONSTRAINT IF EXISTS kb_owner_name_key;
CREATE UNIQUE INDEX uk_kb_owner_role_name ON kb_owner (role, name);
