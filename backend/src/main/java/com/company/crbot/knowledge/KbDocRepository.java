package com.company.crbot.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbDocRepository extends JpaRepository<KbDocEntity, Long> {

    List<KbDocEntity> findAllByOrderByIdDesc();

    List<KbDocEntity> findByStatusOrderByIdAsc(String status);

    boolean existsByIterationId(Long iterationId);
}
