package com.visionary.repository;

import com.visionary.entity.GeneratedArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, Long> {

    List<GeneratedArtifact> findByLearningSessionIdOrderByGmtCreatedDesc(Long learningSessionId);

    List<GeneratedArtifact> findByRunIdOrderByIdAsc(String runId);

    List<GeneratedArtifact> findByMediaStatusInOrderByGmtModifiedAsc(List<String> mediaStatuses);

    void deleteByLearningSessionId(Long learningSessionId);
}
