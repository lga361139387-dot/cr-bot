package com.company.crbot.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewFeedbackSampleRepository extends JpaRepository<ReviewFeedbackSampleEntity, Long> {

    List<ReviewFeedbackSampleEntity> findTop12ByOrderByIdDesc();
}
