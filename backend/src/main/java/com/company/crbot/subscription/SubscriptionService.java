package com.company.crbot.subscription;

import com.company.crbot.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class SubscriptionService {

    private final RepoSubscriptionRepository repository;

    public SubscriptionService(RepoSubscriptionRepository repository) {
        this.repository = repository;
    }

    public List<RepoSubscriptionEntity> list() {
        return repository.findAllByOrderByIdDesc();
    }

    @Transactional
    public RepoSubscriptionEntity create(SaveRequest request) {
        // 新建订阅默认启用
        RepoSubscriptionEntity entity = new RepoSubscriptionEntity();
        apply(entity, request);
        entity.setEnabled(request.enabled() == null || request.enabled());
        ensureUnique(null, entity.getGitlabProjectId(), entity.getBranchPattern());
        return repository.save(entity);
    }

    @Transactional
    public RepoSubscriptionEntity update(Long id, SaveRequest request) {
        RepoSubscriptionEntity entity = require(id);
        apply(entity, request);
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        ensureUnique(id, entity.getGitlabProjectId(), entity.getBranchPattern());
        return repository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        // 订阅删除是物理删除
        repository.delete(require(id));
    }

    public Optional<RepoSubscriptionEntity> match(long projectId, String branch, String eventType) {
        // 只看启用订阅：项目 ID、事件类型、分支模式同时满足才创建任务
        for (RepoSubscriptionEntity subscription : repository.findByEnabledTrueOrderByIdDesc()) {
            if (!subscription.getGitlabProjectId().equals(projectId)) {
                continue;
            }
            if (!eventAllowed(subscription.getEventTypes(), eventType)) {
                continue;
            }
            if (!BranchPatternMatcher.matches(subscription.getBranchPattern(), branch)) {
                continue;
            }
            return Optional.of(subscription);
        }
        return Optional.empty();
    }

    private RepoSubscriptionEntity require(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "订阅不存在"));
    }

    private void apply(RepoSubscriptionEntity entity, SaveRequest request) {
        if (request.gitlabProjectId() == null || request.gitlabProjectId() <= 0) {
            throw new ApiException("GitLab 项目 ID 必须为正整数");
        }
        if (request.projectPath() == null || request.projectPath().isBlank()) {
            throw new ApiException("项目路径不能为空");
        }
        if (request.branchPattern() == null || request.branchPattern().isBlank()) {
            throw new ApiException("分支模式不能为空");
        }
        entity.setGitlabProjectId(request.gitlabProjectId());
        entity.setProjectPath(request.projectPath().trim());
        entity.setBranchPattern(request.branchPattern().trim());
        String events = request.eventTypes() == null || request.eventTypes().isBlank()
                ? "push,merge_request" : request.eventTypes().trim();
        entity.setEventTypes(events);
    }

    private void ensureUnique(Long id, Long projectId, String branch) {
        RepoSubscriptionEntity existing = repository.findByGitlabProjectIdAndBranchPattern(projectId, branch).orElse(null);
        // 同一项目的同一分支只能有一条订阅
        if (existing != null && (id == null || !existing.getId().equals(id))) {
            throw new ApiException("该项目的这个分支已经订阅");
        }
    }

    private boolean eventAllowed(String eventTypes, String eventType) {
        return Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .anyMatch(eventType::equals);
    }

    public record SaveRequest(Long gitlabProjectId, String projectPath, String branchPattern,
                              String eventTypes, Boolean enabled) {
    }
}
