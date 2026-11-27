package com.company.crbot.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbIterationRepository extends JpaRepository<KbIterationEntity, Long> {

    boolean existsByName(String name);

    List<KbIterationEntity> findAllByOrderByIdAsc();
}
