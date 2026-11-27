-- 同一 GitLab 项目的同一分支只允许一条订阅。
CREATE UNIQUE INDEX uk_repo_subscription_project_branch
    ON repo_subscription (gitlab_project_id, branch_pattern);
