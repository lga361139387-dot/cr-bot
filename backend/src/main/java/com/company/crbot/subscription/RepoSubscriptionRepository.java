package com.company.crbot.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoSubscriptionRepository extends JpaRepository<RepoSubscriptionEntity, Long> {

    List<RepoSubscriptionEntity> findByEnabledTrueOrderByIdDesc();

    List<RepoSubscriptionEntity> findAllByOrderByIdDesc();

    Optional<RepoSubscriptionEntity> findByGitlabProjectIdAndBranchPattern(Long gitlabProjectId, String branchPattern);
}
