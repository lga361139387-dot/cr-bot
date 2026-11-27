package com.company.crbot.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewReportRepository extends JpaRepository<ReviewReportEntity, Long> {

    Optional<ReviewReportEntity> findFirstByJobIdOrderByIdDesc(Long jobId);
}
