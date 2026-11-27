package com.company.crbot.datasource;

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
@Table(name = "datasource_config")
public class DatasourceConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "db_type", nullable = false, length = 32)
    private String dbType;

    @Column(name = "jdbc_url", nullable = false, length = 1024)
    private String jdbcUrl;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(name = "password_enc", nullable = false)
    private String passwordEnc;

    @Column(name = "readonly_checked", nullable = false)
    private boolean readonlyChecked;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
