package com.visionary.repository;

import com.visionary.entity.AgentRevisionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRevisionLogRepository extends JpaRepository<AgentRevisionLog, Long> {

    List<AgentRevisionLog> findByArtifactIdOrderByRevisionRoundAsc(Long artifactId);

    Optional<AgentRevisionLog> findByArtifactIdAndRevisionRound(Long artifactId, Integer revisionRound);
}
