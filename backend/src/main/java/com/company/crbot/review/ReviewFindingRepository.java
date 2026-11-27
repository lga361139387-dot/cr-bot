package com.company.crbot.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewFindingRepository extends JpaRepository<ReviewFindingEntity, Long> {

    List<ReviewFindingEntity> findByReportIdOrderByIdAsc(Long reportId);
}
