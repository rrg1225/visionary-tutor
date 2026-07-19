package com.visionary.repository;

import com.visionary.entity.LearningPathEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LearningPathEdgeRepository extends JpaRepository<LearningPathEdge, Long> {
    @Modifying(flushAutomatically = true)
    @Query("delete from LearningPathEdge edge where edge.artifactId = :artifactId")
    int deleteByArtifactId(@Param("artifactId") Long artifactId);

    List<LearningPathEdge> findByArtifactIdOrderByOrderIndexAsc(Long artifactId);
    List<LearningPathEdge> findByLearningSessionId(Long learningSessionId);
    List<LearningPathEdge> findByLearningSessionIdAndToNodeKey(Long learningSessionId, String toNodeKey);
    List<LearningPathEdge> findByLearningSessionIdAndFromNodeKey(Long learningSessionId, String fromNodeKey);
}
