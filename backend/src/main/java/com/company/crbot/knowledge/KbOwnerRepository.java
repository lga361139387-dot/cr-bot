package com.company.crbot.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbOwnerRepository extends JpaRepository<KbOwnerEntity, Long> {

    boolean existsByRoleAndName(Short role, String name);

    List<KbOwnerEntity> findByRoleOrderByIdAsc(Short role);

    boolean existsByGitlabUserId(Long gitlabUserId);
}
