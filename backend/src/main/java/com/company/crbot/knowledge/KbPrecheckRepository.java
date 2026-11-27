package com.company.crbot.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbPrecheckRepository extends JpaRepository<KbPrecheckEntity, Long> {

    Optional<KbPrecheckEntity> findFirstByDocIdOrderByIdDesc(Long docId);

    List<KbPrecheckEntity> findByDocIdAndStatus(Long docId, String status);
}
