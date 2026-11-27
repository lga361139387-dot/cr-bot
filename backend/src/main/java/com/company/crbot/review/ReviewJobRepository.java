package com.company.crbot.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReviewJobRepository extends JpaRepository<ReviewJobEntity, Long>, JpaSpecificationExecutor<ReviewJobEntity> {
}
