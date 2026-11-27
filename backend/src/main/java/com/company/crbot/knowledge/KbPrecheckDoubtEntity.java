package com.company.crbot.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "kb_precheck_doubt")
public class KbPrecheckDoubtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "precheck_id", nullable = false)
    private Long precheckId;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(nullable = false)
    private String excerpt;

    @Column(nullable = false)
    private String question;

    @Column(length = 32)
    private String decision;

    @Column
    private String note;

    @Column(length = 64)
    private String operator;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;
}
