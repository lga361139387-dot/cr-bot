package com.company.crbot.standard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StandardRuleRepository extends JpaRepository<StandardRuleEntity, Long> {

    List<StandardRuleEntity> findByStandardIdOrderByIdAsc(Long standardId);

    Optional<StandardRuleEntity> findByStandardIdAndCode(Long standardId, String code);
}
