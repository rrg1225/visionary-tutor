package com.visionary.repository;

import com.visionary.entity.LearningPathNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LearningPathNodeRepository extends JpaRepository<LearningPathNode, Long> {
    @Modifying(flushAutomatically = true)
    @Query("delete from LearningPathNode node where node.artifactId = :artifactId")
    int deleteByArtifactId(@Param("artifactId") Long artifactId);

    List<LearningPathNode> findByArtifactIdOrderByOrderIndexAsc(Long artifactId);
    List<LearningPathNode> findByLearningSessionId(Long learningSessionId);
    LearningPathNode findByLearningSessionIdAndNodeKey(Long learningSessionId, String nodeKey);
}
