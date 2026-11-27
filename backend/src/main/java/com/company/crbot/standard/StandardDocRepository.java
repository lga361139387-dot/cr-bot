package com.company.crbot.standard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StandardDocRepository extends JpaRepository<StandardDocEntity, Long> {

    List<StandardDocEntity> findByDeletedFalseOrderByIdDesc();

    Optional<StandardDocEntity> findByIdAndDeletedFalse(Long id);

    List<StandardDocEntity> findByActiveTrueAndDeletedFalse();
}
