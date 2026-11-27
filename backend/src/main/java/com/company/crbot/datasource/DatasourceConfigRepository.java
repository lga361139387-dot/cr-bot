package com.company.crbot.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasourceConfigRepository extends JpaRepository<DatasourceConfigEntity, Long> {

    List<DatasourceConfigEntity> findAllByOrderByIdDesc();

    List<DatasourceConfigEntity> findByEnabledTrue();
}
