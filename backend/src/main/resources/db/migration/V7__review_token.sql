-- 一次评审的模型用量。历史任务没有回传 usage，保持 NULL，不记成 0。
ALTER TABLE review_job ADD COLUMN prompt_tokens BIGINT;
ALTER TABLE review_job ADD COLUMN completion_tokens BIGINT;
ALTER TABLE review_job ADD COLUMN total_tokens BIGINT;
