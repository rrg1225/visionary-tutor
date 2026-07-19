package com.visionary.repository;

import com.visionary.entity.LearningMetrics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearningMetricsRepository extends JpaRepository<LearningMetrics, Long> {

    Optional<LearningMetrics> findByUserIdAndKnowledgeConcept(Long userId, String knowledgeConcept);

    List<LearningMetrics> findByUserIdOrderByConfidenceScoreDesc(Long userId);

    void deleteByUserId(Long userId);
}
