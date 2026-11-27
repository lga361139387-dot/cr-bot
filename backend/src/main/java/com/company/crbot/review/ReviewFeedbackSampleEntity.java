package com.company.crbot.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "review_feedback_sample")
public class ReviewFeedbackSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "annotation_id")
    private Long annotationId;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "finding_id")
    private Long findingId;

    @Column(name = "iteration_id")
    private Long iterationId;

    @Column(name = "pusher_name", length = 128)
    private String pusherName;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(length = 32)
    private String category;

    @Column(length = 16)
    private String severity;

    @Column(length = 512)
    private String title;

    @Column(name = "ai_conclusion")
    private String aiConclusion;

    @Column(nullable = false, length = 32)
    private String label;

    @Column
    private String remark;

    @Column(length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
