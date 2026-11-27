package com.company.crbot.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbPrecheckDoubtRepository extends JpaRepository<KbPrecheckDoubtEntity, Long> {

    List<KbPrecheckDoubtEntity> findByPrecheckIdOrderByIdAsc(Long precheckId);
}
