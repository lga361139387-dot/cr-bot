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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "review_finding")
public class ReviewFindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "pusher_name", length = 128)
    private String pusherName;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(nullable = false, length = 512)
    private String title;

    @Column
    private String detail;

    @Column
    private String suggestion;

    @Column(name = "existing_code")
    private String existingCode;

    @Column(name = "suggestion_code")
    private String suggestionCode;

    @Column(name = "location_status")
    private Short locationStatus;

    @Column(name = "evidence_strength")
    private Short evidenceStrength;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refs_json", columnDefinition = "jsonb")
    private String refsJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
