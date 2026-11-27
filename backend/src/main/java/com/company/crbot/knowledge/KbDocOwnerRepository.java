package com.company.crbot.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbDocOwnerRepository extends JpaRepository<KbDocOwnerEntity, Long> {

    List<KbDocOwnerEntity> findByDocId(Long docId);

    boolean existsByOwnerId(Long ownerId);

    void deleteByDocId(Long docId);
}
