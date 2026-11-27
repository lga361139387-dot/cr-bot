package com.company.crbot.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewFindingAnnotationRepository extends JpaRepository<ReviewFindingAnnotationEntity, Long> {

    List<ReviewFindingAnnotationEntity> findByJobIdOrderByIdAsc(Long jobId);

    List<ReviewFindingAnnotationEntity> findByJobIdOrderByIdDesc(Long jobId);

    List<ReviewFindingAnnotationEntity> findByJobIdAndFindingIdOrderByIdAsc(Long jobId, Long findingId);
}
