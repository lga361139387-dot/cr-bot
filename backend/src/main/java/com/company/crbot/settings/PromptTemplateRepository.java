package com.company.crbot.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplateEntity, Long> {

    Optional<PromptTemplateEntity> findFirstByNameAndActiveTrueOrderByIdDesc(String name);

    List<PromptTemplateEntity> findByNameAndActiveTrue(String name);
}
