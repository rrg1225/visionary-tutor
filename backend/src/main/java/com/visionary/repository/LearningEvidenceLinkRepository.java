package com.visionary.repository;

import com.visionary.entity.LearningEvidenceLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningEvidenceLinkRepository extends JpaRepository<LearningEvidenceLink, Long> {
    List<LearningEvidenceLink> findTop200ByUserIdOrderByCreatedAtDesc(Long userId);
}
