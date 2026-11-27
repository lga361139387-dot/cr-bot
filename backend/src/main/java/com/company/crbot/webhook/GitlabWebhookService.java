package com.company.crbot.webhook;

import com.company.crbot.common.ApiException;
import com.company.crbot.review.ReviewJobEntity;
import com.company.crbot.review.ReviewJobService;
import com.company.crbot.settings.SettingsService;
import com.company.crbot.subscription.RepoSubscriptionEntity;
import com.company.crbot.subscription.SubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class GitlabWebhookService {

    private static final Set<String> MR_ACTIONS = Set.of("open", "update", "reopen");

    private final SettingsService settingsService;
    private final SubscriptionService subscriptionService;
    private final ReviewJobService reviewJobService;

    public GitlabWebhookService(SettingsService settingsService, SubscriptionService subscriptionService,
                                ReviewJobService reviewJobService) {
        this.settingsService = settingsService;
        this.subscriptionService = subscriptionService;
        this.reviewJobService = reviewJobService;
    }

    public Map<String, Object> handle(String token, JsonNode body) {
        String secret = settingsService.webhookSecret();
        // 未配置密钥或令牌不匹配都拒绝，避免未授权事件入库
        if (secret.isBlank() || token == null || !constantTimeEquals(secret, token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Webhook Token 无效");
        }
        String kind = body.path("object_kind").asText("");
        if ("push".equals(kind)) {
            return acceptPush(body);
        }
        if ("merge_request".equals(kind)) {
            return acceptMergeRequest(body);
        }
        throw new ApiException("不支持的 GitLab 事件: " + kind);
    }

    private Map<String, Object> acceptPush(JsonNode body) {
        String ref = body.path("ref").asText("");
        if (!ref.startsWith("refs/heads/")) {
            return Map.of("accepted", false, "reason", "只处理分支 push");
        }
        String branch = ref.substring("refs/heads/".length());
        long projectId = body.path("project").path("id").asLong();
        Optional<RepoSubscriptionEntity> subscription = subscriptionService.match(projectId, branch, "push");
        if (subscription.isEmpty()) {
            return Map.of("accepted", false, "reason", "未订阅");
        }
        String after = body.path("after").asText("");
        if (after.isBlank() || after.matches("0+")) {
            return Map.of("accepted", false, "reason", "分支删除事件不评审");
        }
        ReviewJobEntity job = new ReviewJobEntity();
        job.setGitlabProjectId(projectId);
        job.setProjectPath(body.path("project").path("path_with_namespace").asText(subscription.get().getProjectPath()));
        job.setRefBranch(branch);
        job.setEventType("push");
        job.setBeforeSha(body.path("before").asText(null));
        job.setAfterSha(after);
        job.setCommitSha(after);
        job.setPusherName(body.path("user_name").asText(null));
        job.setPusherEmail(body.path("user_email").asText(null));
        job.setTitle(truncate(commitTitle(body), 512));
        job.setStatus("PENDING");
        return saveIdempotent(job);
    }

    private Map<String, Object> acceptMergeRequest(JsonNode body) {
        JsonNode attributes = body.path("object_attributes");
        String action = attributes.path("action").asText("");
        if (!MR_ACTIONS.contains(action)) {
            return Map.of("accepted", false, "reason", "忽略的 MR 动作");
        }
        long projectId = body.path("project").path("id").asLong();
        String target = attributes.path("target_branch").asText("");
        String source = attributes.path("source_branch").asText("");
        Optional<RepoSubscriptionEntity> subscription = subscriptionService.match(projectId, target, "merge_request");
        String branch = target;
        if (subscription.isEmpty()) {
            subscription = subscriptionService.match(projectId, source, "merge_request");
            branch = source;
        }
        if (subscription.isEmpty()) {
            return Map.of("accepted", false, "reason", "未订阅");
        }
        String sha = attributes.path("last_commit").path("id").asText("");
        if (sha.isBlank()) {
            throw new ApiException("MR 事件缺少 last_commit.id");
        }
        ReviewJobEntity job = new ReviewJobEntity();
        job.setGitlabProjectId(projectId);
        job.setProjectPath(body.path("project").path("path_with_namespace").asText(subscription.get().getProjectPath()));
        job.setRefBranch(branch);
        job.setEventType("merge_request");
        job.setMrIid(attributes.path("iid").asInt());
        job.setCommitSha(sha);
        job.setAfterSha(sha);
        job.setPusherName(body.path("user").path("name").asText(null));
        job.setPusherEmail(body.path("user").path("email").asText(null));
        job.setTitle(truncate(attributes.path("title").asText(""), 512));
        job.setStatus("PENDING");
        return saveIdempotent(job);
    }

    private Map<String, Object> saveIdempotent(ReviewJobEntity job) {
        try {
            // 插入放在独立事务里，重复事件回滚后仍返回 200
            reviewJobService.insertPending(job);
            return Map.of("accepted", true, "jobId", job.getId());
        } catch (DataIntegrityViolationException ex) {
            // 同一项目、事件、提交、MR 已入库，Webhook 重放直接返回
            return Map.of("accepted", true, "duplicate", true);
        }
    }

    private String commitTitle(JsonNode body) {
        JsonNode commits = body.path("commits");
        if (commits.isArray() && !commits.isEmpty()) {
            return commits.get(commits.size() - 1).path("message").asText("");
        }
        return body.path("after").asText("");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
