package com.company.crbot.subscription;

import com.company.crbot.gitlab.GitlabClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final GitlabClient gitlabClient;

    public SubscriptionController(SubscriptionService subscriptionService, GitlabClient gitlabClient) {
        this.subscriptionService = subscriptionService;
        this.gitlabClient = gitlabClient;
    }

    @GetMapping
    public List<RepoSubscriptionEntity> list() {
        return subscriptionService.list();
    }

    @GetMapping("/projects")
    public List<GitlabClient.RepoProject> projects() {
        return gitlabClient.listProjects();
    }

    @GetMapping("/projects/{projectId}/branches")
    public List<String> branches(@PathVariable long projectId) {
        return gitlabClient.listBranches(projectId);
    }

    @PostMapping
    public RepoSubscriptionEntity create(@RequestBody SubscriptionService.SaveRequest request) {
        return subscriptionService.create(request);
    }

    @PutMapping("/{id}")
    public RepoSubscriptionEntity update(@PathVariable Long id, @RequestBody SubscriptionService.SaveRequest request) {
        return subscriptionService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        subscriptionService.delete(id);
    }
}
