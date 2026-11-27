-- 开发人员关联的 GitLab 用户。空值表示未关联；同一 Git 账号只能绑一个开发人员。
ALTER TABLE kb_owner ADD COLUMN gitlab_user_id BIGINT;
ALTER TABLE kb_owner ADD COLUMN gitlab_username VARCHAR(128);
CREATE UNIQUE INDEX uk_kb_owner_gitlab_user ON kb_owner (gitlab_user_id);
