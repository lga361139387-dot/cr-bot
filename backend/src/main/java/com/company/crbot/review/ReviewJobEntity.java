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
@Table(name = "review_job")
public class ReviewJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gitlab_project_id", nullable = false)
    private Long gitlabProjectId;

    @Column(name = "project_path", nullable = false)
    private String projectPath;

    @Column(name = "ref_branch", nullable = false)
    private String refBranch;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "mr_iid")
    private Integer mrIid;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "before_sha", length = 64)
    private String beforeSha;

    @Column(name = "after_sha", length = 64)
    private String afterSha;

    @Column(name = "pusher_name", length = 128)
    private String pusherName;

    @Column(name = "pusher_email")
    private String pusherEmail;

    @Column(length = 512)
    private String title;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "prompt_tokens")
    private Long promptTokens;

    @Column(name = "completion_tokens")
    private Long completionTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "rereview_round", nullable = false)
    private Integer rereviewRound;

    @Column(name = "iteration_id")
    private Long iterationId;

    @Column(name = "gate_status", length = 32)
    private String gateStatus;

    @Column(name = "author_role", length = 32)
    private String authorRole;

    @Column(nullable = false)
    private Short voided;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        if (rereviewRound == null) {
            rereviewRound = 0;
        }
        if (voided == null) {
            voided = 0;
        }
    }
}
