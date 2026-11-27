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
@Table(name = "review_finding_annotation")
public class ReviewFindingAnnotationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "finding_id")
    private Long findingId;

    @Column(name = "iteration_id")
    private Long iterationId;

    @Column(name = "pusher_name", length = 128)
    private String pusherName;

    @Column(nullable = false, length = 32)
    private String label;

    @Column
    private String remark;

    @Column(name = "triggered_rereview", nullable = false)
    private boolean triggeredRereview;

    @Column(nullable = false, length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
