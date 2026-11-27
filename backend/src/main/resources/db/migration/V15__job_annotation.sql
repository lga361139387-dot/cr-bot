-- 人工标注改挂在整次评审上，不再强制绑到某一条缺陷。
ALTER TABLE review_finding_annotation ALTER COLUMN finding_id DROP NOT NULL;
